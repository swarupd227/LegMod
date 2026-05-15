package com.envestnet.atlas.diff.service;

import com.envestnet.atlas.diff.agent.TriageAgent;
import com.envestnet.atlas.diff.domain.DiffRunRow;
import com.envestnet.atlas.diff.domain.DivergenceRow;
import com.envestnet.atlas.diff.domain.ReplayRow;
import com.envestnet.atlas.diff.prov.ProvenanceEmitter;
import com.envestnet.atlas.diff.repo.DiffRunRepository;
import com.envestnet.atlas.diff.repo.DivergenceRepository;
import com.envestnet.atlas.diff.repo.ReplayRepository;
import com.envestnet.atlas.diff.replay.CanonicalXml;
import com.envestnet.atlas.diff.replay.Divergence;
import com.envestnet.atlas.diff.replay.Replayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class DifferentialService {
    private static final Logger log = LoggerFactory.getLogger(DifferentialService.class);

    private final DiffRunRepository runs;
    private final ReplayRepository replays;
    private final DivergenceRepository divergences;
    private final TriageAgent agent;
    private final ProvenanceEmitter prov;
    private final S3Client s3;
    private final JdbcTemplate jdbc;
    private final String corpusBucket;

    public DifferentialService(DiffRunRepository runs,
                               ReplayRepository replays,
                               DivergenceRepository divergences,
                               TriageAgent agent,
                               ProvenanceEmitter prov,
                               S3Client s3,
                               JdbcTemplate jdbc,
                               @Value("${MINIO_BUCKET_CORPUS:atlas-corpus}") String corpusBucket) {
        this.runs = runs;
        this.replays = replays;
        this.divergences = divergences;
        this.agent = agent;
        this.prov = prov;
        this.s3 = s3;
        this.jdbc = jdbc;
        this.corpusBucket = corpusBucket;
    }

    @Transactional
    public DiffRunRow run(UUID projectId, int sampleSize) throws Exception {
        log.info("Differential run starting · project={} sample={}", projectId, sampleSize);

        DiffRunRow run = runs.save(new DiffRunRow(
                null, projectId, OffsetDateTime.now(), null,
                "running", 0, 0, 0, 0, 0, false, "{}"
        ));

        // Pull cap.envelope rows directly via JdbcTemplate so we don't need to
        // import cap-service's domain types here.
        List<EnvelopeRef> envs = jdbc.query("""
            SELECT id, operation_name, direction, storage_uri
              FROM cap.envelope
             WHERE project_id = ?
             ORDER BY captured_at ASC
             LIMIT ?
            """, (rs, i) -> new EnvelopeRef(
                    UUID.fromString(rs.getString(1)),
                    rs.getString(2), rs.getString(3), rs.getString(4)),
                projectId, sampleSize);

        if (envs.isEmpty()) {
            jdbc.update("UPDATE diff.run SET status='failed', finished_at=now(), summary=?::jsonb WHERE id=?",
                    "{\"error\":\"no envelopes captured — run Stage B first\"}", run.id());
            return runs.findById(run.id()).orElse(run);
        }

        CanonicalXml canon = new CanonicalXml();
        Replayer replayer = new Replayer(canon);

        // Aggregate every divergence so the agent can batch-classify in one shot.
        List<Divergence> allDivs = new ArrayList<>();
        List<UUID> divReplayIds = new ArrayList<>();
        List<UUID> replayIds = new ArrayList<>();

        int pass = 0, benign = 0, amber = 0, red = 0;
        int idx = 0;
        for (EnvelopeRef ev : envs) {
            String legacyXml = readObject(ev.storageUri());
            if (legacyXml == null) {
                idx++;
                continue;
            }
            Replayer.Result r = replayer.replay(idx, ev.operationName(), ev.direction(), legacyXml);

            // Heuristic worst-bucket per envelope
            String worst = "pass";
            for (Divergence d : r.divergences()) {
                if ("red".equals(d.bucket)) worst = "red";
                else if ("amber".equals(d.bucket) && !"red".equals(worst)) worst = "amber";
                else if ("benign".equals(d.bucket) && !"red".equals(worst) && !"amber".equals(worst)) worst = "benign";
            }
            switch (worst) {
                case "red"    -> red++;
                case "amber"  -> amber++;
                case "benign" -> benign++;
                default       -> pass++;
            }

            String firstKind = r.divergences().isEmpty() ? null : r.divergences().get(0).kind;
            ReplayRow rep = replays.save(new ReplayRow(
                    null, run.id(), projectId, ev.id(), ev.operationName(), ev.direction(),
                    worst, r.divergences().size(), firstKind,
                    r.divergences().isEmpty()
                        ? "byte-equivalent"
                        : r.divergences().size() + " divergence(s)"
            ));
            replayIds.add(rep.id());
            for (Divergence d : r.divergences()) {
                allDivs.add(d);
                divReplayIds.add(rep.id());
            }
            idx++;
        }

        // Batch-classify divergences in chunks of 50.
        int batchSize = 50;
        List<TriageAgent.Verdict> verdicts = new ArrayList<>(allDivs.size());
        for (int i = 0; i < allDivs.size(); i += batchSize) {
            List<Divergence> chunk = allDivs.subList(i, Math.min(i + batchSize, allDivs.size()));
            verdicts.addAll(agent.classify(chunk));
        }
        // Pad if the agent returned fewer than expected (shouldn't happen).
        while (verdicts.size() < allDivs.size()) {
            verdicts.add(TriageAgent.Verdict.fromHeuristic(
                    allDivs.get(verdicts.size()), null, true, "agent under-returned"));
        }

        // Persist divergences with verdicts.
        boolean anyStub = false;
        for (int i = 0; i < allDivs.size(); i++) {
            Divergence d = allDivs.get(i);
            TriageAgent.Verdict v = verdicts.get(i);
            if (v.stub()) anyStub = true;
            divergences.save(new DivergenceRow(
                    null, divReplayIds.get(i), projectId,
                    null, d.kind, v.bucket(), d.xpath, d.legacyValue, d.newValue,
                    d.humanSummary, v.action(), v.rationale(),
                    v.model(), v.stub(),
                    null,                       // autofix_proposal — populated by /auto-fix
                    false, null, null
            ));
        }

        String summary = String.format(
                "{\"envelopes\":%d,\"pass\":%d,\"benign\":%d,\"amber\":%d,\"red\":%d,\"agent_stub\":%s}",
                envs.size(), pass, benign, amber, red, anyStub);
        jdbc.update("""
            UPDATE diff.run SET status='completed', finished_at=now(),
              envelopes_replayed=?, pass_count=?, benign_count=?, amber_count=?, red_count=?,
              summary=?::jsonb WHERE id=?
            """,
            envs.size(), pass, benign, amber, red, summary, run.id());

        // Run-level provenance event.
        ProvenanceEmitter.Event runEv = ProvenanceEmitter.Event.of(
                projectId, "system", "diff-service", "differential_run_completed");
        runEv.output = Map.of(
                "envelopes", envs.size(),
                "pass", pass, "benign", benign, "amber", amber, "red", red,
                "agent_stub", anyStub);
        runEv.links = List.of("run:" + run.id());
        prov.emit(runEv);

        // Triage agent batch event (one per run, summarising all classifications).
        if (!allDivs.isEmpty()) {
            ProvenanceEmitter.Event triageEv = ProvenanceEmitter.Event.of(
                    projectId, "agent", "diff-triage", "triage_batch_classified");
            triageEv.prompt = Map.of("divergences", allDivs.size());
            triageEv.output = Map.of(
                    "benign", benign, "amber", amber, "red", red,
                    "stub", anyStub);
            triageEv.links = List.of("run:" + run.id());
            prov.emit(triageEv);
        }

        log.info("Differential run done · pass={} benign={} amber={} red={} stub={}",
                pass, benign, amber, red, anyStub);
        return runs.findById(run.id()).orElse(run);
    }

    public Map<String, Object> status(UUID projectId) {
        Optional<DiffRunRow> r = runs.latest(projectId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", r.map(this::runMap).orElse(null));
        if (r.isEmpty()) {
            out.put("byOperation", List.of());
            out.put("buckets", Map.of("benign", 0, "amber", 0, "red", 0));
            out.put("divergences", List.of());
            return out;
        }

        List<ReplayRow> reps = replays.findByRun(r.get().id());
        out.put("replays", reps.stream().map(this::replayMap).toList());

        // Per-operation summary
        Map<String, int[]> byOp = new LinkedHashMap<>();
        for (ReplayRow rep : reps) {
            int[] counts = byOp.computeIfAbsent(rep.operationName(), k -> new int[4]);
            switch (rep.bucket()) {
                case "pass"   -> counts[0]++;
                case "benign" -> counts[1]++;
                case "amber"  -> counts[2]++;
                case "red"    -> counts[3]++;
            }
        }
        List<Map<String, Object>> opList = new ArrayList<>();
        for (var e : byOp.entrySet()) {
            int[] c = e.getValue();
            int total = c[0] + c[1] + c[2] + c[3];
            opList.add(Map.of(
                    "name", e.getKey(),
                    "total", total,
                    "pass", c[0],
                    "benign", c[1],
                    "amber", c[2],
                    "red", c[3]
            ));
        }
        out.put("byOperation", opList);

        // Compute buckets from the SAME divergence list we render so the
        // header counts and the queue list never disagree. (countByBucket
        // queries across all historical runs and double-counts re-runs.)
        List<DivergenceRow> latest = divergences.findByRun(r.get().id());
        long benignDiv = latest.stream().filter(d -> "benign".equals(d.bucket())).count();
        long amberDiv  = latest.stream().filter(d -> "amber".equals(d.bucket())).count();
        long redDiv    = latest.stream().filter(d -> "red".equals(d.bucket())).count();
        out.put("buckets", Map.of("benign", benignDiv, "amber", amberDiv, "red", redDiv));

        out.put("divergences", latest.stream().map(this::divergenceMap).toList());
        return out;
    }

    public Map<String, Object> autoFix(UUID divId, String user) {
        Optional<DivergenceRow> opt = divergences.findById(divId);
        if (opt.isEmpty()) return Map.of("error", "not found");
        DivergenceRow d = opt.get();
        // For Phase 1e the auto-fix is a structured patch description. The
        // engineer accepts it; binding regeneration happens in Stage D.
        String patch = autoFixPatch(d);
        jdbc.update("""
            UPDATE diff.divergence
               SET autofix_proposal = ?::jsonb,
                   resolved = TRUE,
                   resolved_by = ?,
                   resolved_at = now()
             WHERE id = ?
            """, patch, user, divId);

        // Provenance — human accepted an auto-fix.
        ProvenanceEmitter.Event ev = ProvenanceEmitter.Event.of(
                d.projectId(), "human", user == null ? "unknown" : user,
                "auto_fix_applied");
        ev.output = Map.of(
                "kind", d.kind(),
                "bucket", d.bucket(),
                "agent_action", d.agentAction(),
                "patch", patch);
        ev.links = List.of("divergence:" + d.id());
        prov.emit(ev);

        return Map.of("ok", true, "patch", patch);
    }

    private String autoFixPatch(DivergenceRow d) {
        return switch (d.kind()) {
            case "date_format" -> """
                {"binding_file":"bindings.xjb","change":"Add MM/dd/yyyy XmlAdapter on tradeDate/settlementDate","action":"%s"}
                """.formatted(d.agentAction());
            case "type_precision" -> """
                {"binding_file":"bindings.xjb","change":"Pin xsd:decimal scale=2 on amount/value","action":"%s"}
                """.formatted(d.agentAction());
            default -> """
                {"action":"%s","note":"requires manual binding edit"}
                """.formatted(d.agentAction());
        };
    }

    /* ---------------- helpers ---------------- */

    private String readObject(String storageUri) {
        // storageUri looks like "s3://bucket/key"
        if (storageUri == null || !storageUri.startsWith("s3://")) return null;
        String rest = storageUri.substring(5);
        int slash = rest.indexOf('/');
        if (slash < 0) return null;
        String bucket = rest.substring(0, slash);
        String key = rest.substring(slash + 1);
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder()
                .bucket(bucket).key(key).build())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("could not read {}: {}", storageUri, e.toString());
            return null;
        }
    }

    private Map<String, Object> runMap(DiffRunRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("startedAt", r.startedAt());
        m.put("finishedAt", r.finishedAt());
        m.put("status", r.status());
        m.put("envelopesReplayed", r.envelopesReplayed());
        m.put("passCount", r.passCount());
        m.put("benignCount", r.benignCount());
        m.put("amberCount", r.amberCount());
        m.put("redCount", r.redCount());
        m.put("isPromoted", r.isPromoted());
        return m;
    }

    private Map<String, Object> replayMap(ReplayRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("envelopeId", r.envelopeId());
        m.put("operation", r.operationName());
        m.put("direction", r.direction());
        m.put("bucket", r.bucket());
        m.put("diffCount", r.diffCount());
        m.put("firstKind", r.firstKind());
        m.put("summary", r.summary());
        return m;
    }

    private Map<String, Object> divergenceMap(DivergenceRow d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id());
        m.put("replayId", d.replayId());
        m.put("operation", d.operationName());
        m.put("kind", d.kind());
        m.put("bucket", d.bucket());
        m.put("xpath", d.xpath());
        m.put("legacyValue", d.legacyValue());
        m.put("newValue", d.newValue());
        m.put("humanSummary", d.humanSummary());
        m.put("agent", Map.of(
                "action", d.agentAction(),
                "rationale", d.agentRationale(),
                "model", d.agentModel(),
                "stub", d.agentStub()
        ));
        m.put("autofixProposal", d.autofixProposal());
        m.put("resolved", d.resolved());
        m.put("resolvedBy", d.resolvedBy());
        m.put("resolvedAt", d.resolvedAt());
        return m;
    }

    public record EnvelopeRef(UUID id, String operationName, String direction, String storageUri) {}
}
