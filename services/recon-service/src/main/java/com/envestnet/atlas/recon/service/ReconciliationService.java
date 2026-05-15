package com.envestnet.atlas.recon.service;

import com.envestnet.atlas.recon.agent.ReconciliationAgent;
import com.envestnet.atlas.recon.domain.DecisionRow;
import com.envestnet.atlas.recon.domain.ElementRow;
import com.envestnet.atlas.recon.domain.RunRow;
import com.envestnet.atlas.recon.prov.ProvenanceEmitter;
import com.envestnet.atlas.recon.repo.DecisionRepository;
import com.envestnet.atlas.recon.repo.ElementRepository;
import com.envestnet.atlas.recon.repo.RunRepository;
import com.envestnet.atlas.recon.schema.*;
import com.envestnet.atlas.recon.synth.AuthoritativeWsdl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class ReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final RunRepository runs;
    private final ElementRepository elements;
    private final DecisionRepository decisions;
    private final EmpiricalInferrer inferrer;
    private final CodeViewLoader codeLoader;
    private final ReconciliationAgent agent;
    private final ProvenanceEmitter prov;
    private final JdbcTemplate jdbc;
    private final S3Client s3;
    private final String artifactBucket;
    private final RestTemplate http;
    private final String archUrl;
    private final ObjectMapper json = new ObjectMapper();

    public ReconciliationService(RunRepository runs,
                                 ElementRepository elements,
                                 DecisionRepository decisions,
                                 EmpiricalInferrer inferrer,
                                 CodeViewLoader codeLoader,
                                 ReconciliationAgent agent,
                                 ProvenanceEmitter prov,
                                 JdbcTemplate jdbc,
                                 S3Client s3,
                                 RestTemplate http,
                                 @Value("${ARCH_SERVICE_URL:http://arch-service:8083}") String archUrl,
                                 @Value("${MINIO_BUCKET_ARTIFACTS:atlas-artifacts}") String artifactBucket) {
        this.runs = runs;
        this.elements = elements;
        this.decisions = decisions;
        this.inferrer = inferrer;
        this.codeLoader = codeLoader;
        this.agent = agent;
        this.prov = prov;
        this.jdbc = jdbc;
        this.s3 = s3;
        this.http = http;
        this.archUrl = archUrl;
        this.artifactBucket = artifactBucket;
    }

    @Transactional
    public RunRow run(UUID projectId, String sourcePath) throws Exception {
        log.info("Reconciliation run · project={}", projectId);

        // Wipe prior decisions/elements so re-runs start clean.
        decisions.deleteByProject(projectId);
        elements.deleteByProject(projectId);

        RunRow run = runs.save(new RunRow(null, projectId, OffsetDateTime.now(),
                null, "running", "{}"));

        // Load views.
        Map<String, ElementInfo> vendorView;
        try {
            vendorView = sourcePath == null
                    ? Map.of()
                    : new VendorWsdlParser().parseAll(Path.of(sourcePath));
        } catch (Exception e) {
            log.warn("vendor parse failed: {}", e.toString());
            vendorView = Map.of();
        }
        Map<String, ElementInfo> codeView    = codeLoader.load(projectId);
        EmpiricalInferrer.Result emp         = inferrer.infer(projectId, 250);

        Merger.Output merged = new Merger().merge(vendorView, codeView, emp.elements());

        // Persist elements; capture element ids to attach to decisions.
        Map<String, UUID> idByPath = new LinkedHashMap<>();
        for (Merger.ElementRecord e : merged.elements) {
            UUID elemId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO recon.element
                  (id, project_id, run_id, path, vendor_view, code_view, empiric_view)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)
                """,
                elemId, projectId, run.id(), e.path(),
                writeJson(e.vendor().toMap()),
                writeJson(e.code().toMap()),
                writeJson(e.empiric().toMap()));
            idByPath.put(e.path(), elemId);
        }

        // Run agent per divergence and persist decisions.
        int agentStub = 0;
        int total = 0;
        for (Merger.DivergenceRecord d : merged.divergences) {
            ReconciliationAgent.Recommendation rec = agent.recommend(
                    d.path(), d.kind(), d.vendor(), d.code(), d.empiric());
            if (rec.stub()) agentStub++;

            DecisionRow row = new DecisionRow(
                    null, projectId, run.id(), idByPath.get(d.path()), d.path(),
                    d.kind(), d.impact(),
                    rec.action(), rec.rationale(),
                    rec.alternatives() == null ? new String[0] : rec.alternatives().toArray(new String[0]),
                    rec.model(), rec.stub(),
                    rec.confidence(),
                    "pending",                      // resolution
                    null, null, null, null, null,
                    OffsetDateTime.now()
            );
            DecisionRow saved = decisions.save(row);
            total++;

            // Provenance — one entry per agent decision classification.
            ProvenanceEmitter.Event ev = ProvenanceEmitter.Event.of(
                    projectId, "agent", "reconciliation", "decision_classified");
            ev.prompt = Map.of(
                    "path", d.path(),
                    "divergence_kind", d.kind(),
                    "impact", d.impact());
            ev.model = rec.model();
            ev.output = Map.of(
                    "action", rec.action(),
                    "rationale", rec.rationale(),
                    "confidence", rec.confidence(),
                    "stub", rec.stub());
            ev.links = List.of("decision:" + saved.id());
            prov.emit(ev);
        }

        String summary = String.format(
                "{\"elements\":%d,\"decisions\":%d,\"envelopes_scanned\":%d,\"agent_stub\":%s}",
                merged.elements.size(), total, emp.envelopesScanned(), agentStub > 0);

        jdbc.update("UPDATE recon.run SET status='completed', finished_at=now(), summary=?::jsonb WHERE id=?",
                summary, run.id());

        // Run-level event.
        ProvenanceEmitter.Event runEv = ProvenanceEmitter.Event.of(
                projectId, "system", "recon-service", "reconciliation_run_completed");
        runEv.output = Map.of(
                "elements", merged.elements.size(),
                "decisions", total,
                "envelopes_scanned", emp.envelopesScanned(),
                "agent_stub", agentStub > 0);
        runEv.links = List.of("run:" + run.id());
        prov.emit(runEv);

        log.info("Reconciliation run done · elements={} decisions={} stub-agent={}",
                merged.elements.size(), total, agentStub);
        return runs.findById(run.id()).orElse(run);
    }

    public Map<String, Object> status(UUID projectId) {
        Optional<RunRow> r = runs.latest(projectId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", r.orElse(null));

        List<DecisionRow> ds = decisions.findByProject(projectId);
        out.put("decisions", ds.stream().map(this::decisionMap).toList());

        long pending = ds.stream().filter(d -> "pending".equals(d.resolution())).count();
        long resolved = ds.size() - pending;
        out.put("counts", Map.of("total", ds.size(), "pending", pending, "resolved", resolved));

        if (r.isPresent()) {
            List<Map<String, Object>> elems = new ArrayList<>();
            for (ElementRow e : elements.findByRun(r.get().id())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.id());
                m.put("path", e.path());
                m.put("vendorView", parseJson(e.vendorView()));
                m.put("codeView", parseJson(e.codeView()));
                m.put("empiricView", parseJson(e.empiricView()));
                elems.add(m);
            }
            out.put("elements", elems);
        } else {
            out.put("elements", List.of());
        }
        return out;
    }

    /**
     * Look up a single decision by id. Used by prj-service to read
     * kind / path / chosenAction after a resolve so it can record the
     * decision in the cross-project pattern library. Returns null on
     * unknown id.
     */
    public Map<String, Object> getDecision(UUID decisionId) {
        return decisions.findById(decisionId)
                .map(this::decisionMap)
                .orElse(null);
    }

    @Transactional
    public Map<String, Object> resolve(UUID decisionId, ResolveRequest req) {
        Optional<DecisionRow> opt = decisions.findById(decisionId);
        if (opt.isEmpty()) return Map.of("error", "decision not found");

        DecisionRow d = opt.get();
        DecisionRow updated = new DecisionRow(
                d.id(), d.projectId(), d.runId(), d.elementId(), d.path(),
                d.kind(), d.impact(),
                d.agentAction(), d.agentRationale(), d.agentAlts(),
                d.agentModel(), d.agentStub(),
                d.confidence(),
                req.choice(),
                req.action() == null ? d.agentAction() : req.action(),
                d.chosenPayload(),
                req.note(),
                req.user(),
                OffsetDateTime.now(),
                d.createdAt()
        );
        decisions.save(updated);

        // Provenance — human resolution.
        ProvenanceEmitter.Event ev = ProvenanceEmitter.Event.of(
                d.projectId(), "human", req.user() == null ? "unknown" : req.user(),
                "decision_resolved");
        ev.output = Map.of(
                "path", d.path(),
                "choice", req.choice(),
                "action", req.action() == null ? d.agentAction() : req.action(),
                "agent_action", d.agentAction());
        ev.humanReview = Map.of(
                "reviewer", req.user() == null ? "unknown" : req.user(),
                "note", req.note() == null ? "" : req.note());
        ev.links = List.of("decision:" + d.id());
        prov.emit(ev);

        // If this resolution closed the queue, synthesise the A-WSDL.
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("id", d.id().toString());
        response.put("resolution", req.choice());

        long pending = decisions.findByProject(d.projectId()).stream()
                .filter(x -> "pending".equals(x.resolution())).count();
        if (pending == 0) {
            String uri = synthesiseAuthoritativeWsdl(d.projectId());
            if (uri != null) response.put("aWsdlUri", uri);
        }
        return response;
    }

    /**
     * Build the Authoritative WSDL from the latest run's elements and the project's
     * resolved decisions, upload to MinIO, and return the s3:// URI.
     */
    public String synthesiseAuthoritativeWsdl(UUID projectId) {
        try {
            Optional<RunRow> rOpt = runs.latest(projectId);
            if (rOpt.isEmpty()) return null;
            UUID runId = rOpt.get().id();

            List<ElementRow> elems = elements.findByRun(runId);
            List<DecisionRow> decs = decisions.findByProject(projectId);
            // Operation names from the arch run — gives the synthesizer
            // a complete set of <portType>/<binding>/<service> entries
            // even when the decisions don't include the legacy
            // *Return-suffixed types. wsimport refuses to generate
            // anything for a types-only WSDL, so this matters in
            // practice for real-world codebases.
            List<String> opNames = fetchProjectOperations(projectId);

            String wsdl = new AuthoritativeWsdl().emit(projectId, elems, decs, opNames);
            byte[] bytes = wsdl.getBytes(StandardCharsets.UTF_8);

            String key = projectId + "/authoritative.wsdl";
            s3.putObject(PutObjectRequest.builder()
                            .bucket(artifactBucket).key(key)
                            .contentType("application/xml").build(),
                    RequestBody.fromBytes(bytes));

            String uri = "s3://" + artifactBucket + "/" + key;
            log.info("authoritative WSDL synthesised · project={} bytes={} uri={}",
                    projectId, bytes.length, uri);
            return uri;
        } catch (Exception e) {
            log.warn("A-WSDL synth failed for project {}: {}", projectId, e.toString());
            return null;
        }
    }

    /**
     * Look up the operation names the arch agent extracted for this
     * project. Returns an empty list on any failure (network, 404,
     * service down) — the synthesizer then falls back to its legacy
     * *Return-type heuristic. We never want a transient arch-service
     * blip to stop WSDL synthesis.
     */
    @SuppressWarnings("unchecked")
    private List<String> fetchProjectOperations(UUID projectId) {
        try {
            Object[] resp = http.getForObject(
                    archUrl + "/internal/archaeology/operations/" + projectId,
                    Object[].class);
            if (resp == null) return List.of();
            List<String> names = new ArrayList<>();
            for (Object item : resp) {
                if (item instanceof Map<?, ?> m) {
                    Object n = m.get("name");
                    if (n != null) names.add(n.toString());
                }
            }
            log.info("arch operations · project={} count={}", projectId, names.size());
            return names;
        } catch (Exception e) {
            log.warn("arch operations fetch failed · project={} · {}", projectId, e.toString());
            return List.of();
        }
    }

    public String fetchAuthoritativeWsdl(UUID projectId) {
        String key = projectId + "/authoritative.wsdl";
        try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder()
                .bucket(artifactBucket).key(key).build())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> decisionMap(DecisionRow d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id());
        m.put("path", d.path());
        m.put("kind", d.kind());
        m.put("impact", d.impact());
        m.put("agent", Map.of(
                "action", d.agentAction(),
                "rationale", d.agentRationale(),
                "alternatives", d.agentAlts() == null ? List.of() : Arrays.asList(d.agentAlts()),
                "model", d.agentModel(),
                "stub", d.agentStub()
        ));
        m.put("confidence", d.confidence());
        m.put("resolution", d.resolution());
        m.put("chosenAction", d.chosenAction());
        m.put("note", d.note());
        m.put("resolvedBy", d.resolvedBy());
        m.put("resolvedAt", d.resolvedAt());
        return m;
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { return "{}"; }
    }
    private Object parseJson(String s) {
        if (s == null) return Map.of();
        try { return json.readValue(s, Object.class); } catch (Exception e) { return Map.of(); }
    }

    public record ResolveRequest(String choice, String action, String note, String user) {}
}
