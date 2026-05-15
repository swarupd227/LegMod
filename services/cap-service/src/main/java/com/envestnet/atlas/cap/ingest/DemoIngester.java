package com.envestnet.atlas.cap.ingest;

import com.envestnet.atlas.cap.domain.Deployment;
import com.envestnet.atlas.cap.domain.Envelope;
import com.envestnet.atlas.cap.domain.SanitizationRule;
import com.envestnet.atlas.cap.repo.DeploymentRepository;
import com.envestnet.atlas.cap.repo.EnvelopeRepository;
import com.envestnet.atlas.cap.repo.SanitizationRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase-1b shortcut: instead of running a real capture sidecar against a live
 * Axis service, we ingest the bundled sample envelopes (request/response)
 * and synthesize realistic variants — different accounts, dates, amounts,
 * occasional faults. Output looks identical to a real capture run from the
 * console's perspective: rows in cap.envelope, blobs in MinIO, sanitization
 * counts updated.
 */
@Component
public class DemoIngester {
    private static final Logger log = LoggerFactory.getLogger(DemoIngester.class);

    private static final String[] OPERATIONS = {
        "submitAllocation", "getAllocationStatus", "batchAllocate", "cancelAllocation"
    };
    private static final String[] PARTNERS = { "Broadridge", "Pershing", "LPL" };
    private static final String[] ENVS = { "QUAT-1", "QUAT-2" };
    private static final String[] FUNDS = { "VFIAX", "VTSAX", "FXAIX", "SWPPX", "SCHB" };
    private static final String[] ACCT_TYPES = { "RETAIL", "RETIREMENT", "INSTITUTIONAL" };

    private final S3Client s3;
    private final String bucket;
    private final DeploymentRepository deployments;
    private final EnvelopeRepository envelopes;
    private final SanitizationRuleRepository rules;
    private final JdbcTemplate jdbc;

    public DemoIngester(S3Client s3,
                        @Value("${MINIO_BUCKET_CORPUS:atlas-corpus}") String bucket,
                        DeploymentRepository deployments,
                        EnvelopeRepository envelopes,
                        SanitizationRuleRepository rules,
                        JdbcTemplate jdbc) {
        this.s3 = s3;
        this.bucket = bucket;
        this.deployments = deployments;
        this.envelopes = envelopes;
        this.rules = rules;
        this.jdbc = jdbc;
    }

    public Result ingest(UUID projectId, String sourcePath, int targetEnvelopes) throws IOException {
        // 1. Provision two synthetic deployments so the console has rows.
        ensureDeployments(projectId);
        deployments.markLive(projectId);

        // 2. Wipe prior envelopes for clean re-runs.
        envelopes.deleteByProject(projectId);

        // 3. Read the seed envelopes from samples/.
        Map<String, String> seed = loadSeed(Path.of(sourcePath));
        if (seed.isEmpty()) {
            log.warn("no seed envelopes under {}", sourcePath);
        }

        // 4. Spin up a sanitizer with active rules.
        List<SanitizationRule> active = rules.activeFor(projectId);
        Sanitizer sanitizer = new Sanitizer(active);
        long[] aggregateHits = new long[active.size()];

        // 5. Resolve the live deployments so we can attribute each envelope to
        //    a specific one — round-robin keyed by ENVS index keeps the demo
        //    distribution even.
        Map<String, UUID> deploymentByEnv = new LinkedHashMap<>();
        for (Deployment d : deployments.findByProject(projectId)) {
            deploymentByEnv.put(d.environment(), d.id());
        }

        // 6. Generate envelopes with rotation across operations/partners/envs.
        Random rnd = new Random(42L); // deterministic for the demo
        Map<String, Integer> opCounts = new LinkedHashMap<>();
        int faultCount = 0;
        for (int i = 0; i < targetEnvelopes; i++) {
            String op = OPERATIONS[i % OPERATIONS.length];
            String partner = PARTNERS[rnd.nextInt(PARTNERS.length)];
            String env = ENVS[rnd.nextInt(ENVS.length)];
            UUID depId = deploymentByEnv.get(env);
            boolean isFault = rnd.nextInt(100) < 2;   // ~2 % faults
            boolean isResp = rnd.nextInt(100) < 50;
            String direction = isFault ? "FAULT" : isResp ? "RESPONSE" : "REQUEST";

            String body = synthesize(seed, op, direction, rnd);
            Sanitizer.Result san = sanitizer.apply(body);
            for (int j = 0; j < san.perRuleHits().length && j < aggregateHits.length; j++) {
                aggregateHits[j] += san.perRuleHits()[j];
            }

            String key = String.format("%s/%s/%s.xml", projectId, op, UUID.randomUUID());
            byte[] bytes = san.text().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                    .contentType("application/xml").build(),
                    RequestBody.fromBytes(bytes));

            envelopes.save(new Envelope(
                    null, projectId, depId, op, direction,
                    OffsetDateTime.now().minusMinutes(rnd.nextInt(60 * 24 * 14)),
                    partner, env, bytes.length,
                    "s3://" + bucket + "/" + key,
                    san.totalHits(),
                    "corr-" + Long.toHexString(rnd.nextLong()).substring(0, 8)
            ));
            if (isFault) faultCount++;
            opCounts.merge(op, 1, Integer::sum);
        }

        // 6. Persist aggregate sanitization hits back onto the rules table.
        for (int i = 0; i < active.size() && i < aggregateHits.length; i++) {
            jdbc.update("UPDATE cap.sanitization_rule SET hits = hits + ? WHERE id = ?",
                    aggregateHits[i], active.get(i).id());
        }

        log.info("demo ingest done · project={} envelopes={} faults={}",
                projectId, targetEnvelopes, faultCount);
        return new Result(targetEnvelopes, faultCount, opCounts);
    }

    public void finalize(UUID projectId) {
        deployments.stopAll(projectId);
    }

    /* ---------------- helpers ---------------- */

    private void ensureDeployments(UUID projectId) {
        List<Deployment> existing = deployments.findByProject(projectId);
        Set<String> envSet = new HashSet<>();
        for (Deployment d : existing) envSet.add(d.environment());
        OffsetDateTime now = OffsetDateTime.now();
        for (String env : ENVS) {
            if (envSet.contains(env)) continue;
            deployments.save(new Deployment(
                    null, projectId, env, "demo",
                    "pending", 100, 65536, "demo-v1",
                    null, null, null, now));
        }
    }

    private Map<String, String> loadSeed(Path sampleRoot) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(sampleRoot)) return out;
        Path resources = sampleRoot.resolve("src").resolve("main").resolve("resources");
        if (Files.isDirectory(resources)) {
            try (var s = Files.list(resources)) {
                s.filter(p -> p.toString().endsWith(".xml")).forEach(p -> {
                    try {
                        out.put(p.getFileName().toString(), Files.readString(p));
                    } catch (IOException ignored) {}
                });
            }
        }
        return out;
    }

    private String synthesize(Map<String, String> seed, String op, String direction, Random rnd) {
        String base;
        if ("RESPONSE".equals(direction) || "FAULT".equals(direction)) {
            base = seed.getOrDefault("sample-response.xml", DEFAULT_RESPONSE);
        } else {
            base = seed.getOrDefault("sample-request.xml", DEFAULT_REQUEST);
        }

        // Substitute operation name (the seed envelope is for submitAllocation).
        base = base.replace("submitAllocation", op);

        // Account, fund, amount, date, correlation
        String acct = "ACC-" + (90000 + rnd.nextInt(99999));
        String fund = FUNDS[rnd.nextInt(FUNDS.length)];
        BigDecimal amt = new BigDecimal(1000 + rnd.nextInt(50000)).setScale(2);
        String tradeDate = String.format("%02d/%02d/2025",
                1 + rnd.nextInt(12), 1 + rnd.nextInt(28));
        String corr = "corr-" + Long.toHexString(rnd.nextLong()).substring(0, 8);

        base = swap(base, "<tt:accountId>", "</tt:accountId>", acct);
        base = swap(base, "<tt:fundSymbol>", "</tt:fundSymbol>", fund);
        base = swap(base, "<tt:value>", "</tt:value>", amt.toPlainString());
        base = swap(base, "<tt:tradeDate>", "</tt:tradeDate>", tradeDate);
        base = swap(base, "<tt:settlementDate>", "</tt:settlementDate>", tradeDate);
        base = swap(base, "<tt:correlationId>", "</tt:correlationId>", corr);
        base = swap(base, "<tt:accountType>", "</tt:accountType>",
                ACCT_TYPES[rnd.nextInt(ACCT_TYPES.length)]);

        if ("FAULT".equals(direction)) {
            // Wrap in a soap:Fault inside Body; quick-and-dirty for the demo.
            base = base.replace("<soapenv:Body>",
                    "<soapenv:Body><soapenv:Fault><faultcode>soapenv:Client</faultcode>" +
                    "<faultstring>Allocation rejected: insufficient funds</faultstring></soapenv:Fault>");
        }
        return base;
    }

    private static String swap(String body, String openTag, String closeTag, String value) {
        Pattern p = Pattern.compile(Pattern.quote(openTag) + ".*?" + Pattern.quote(closeTag));
        Matcher m = p.matcher(body);
        return m.replaceAll(Matcher.quoteReplacement(openTag + value + closeTag));
    }

    public record Result(int total, int faults, Map<String, Integer> perOperation) {}

    private static final String DEFAULT_REQUEST =
            "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"" +
            " xmlns:br=\"http://broadridge.com/services/mf\"" +
            " xmlns:tt=\"http://broadridge.com/services/mf/types\">" +
            "<soapenv:Body><br:submitAllocation><br:request>" +
            "<tt:accountId>ACC-00001</tt:accountId>" +
            "<tt:fundSymbol>VFIAX</tt:fundSymbol>" +
            "<tt:tradeDate>01/01/2025</tt:tradeDate>" +
            "</br:request></br:submitAllocation></soapenv:Body></soapenv:Envelope>";
    private static final String DEFAULT_RESPONSE = DEFAULT_REQUEST;
}
