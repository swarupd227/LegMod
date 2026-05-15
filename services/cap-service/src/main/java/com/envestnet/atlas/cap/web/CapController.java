package com.envestnet.atlas.cap.web;

import com.envestnet.atlas.cap.domain.Deployment;
import com.envestnet.atlas.cap.domain.Envelope;
import com.envestnet.atlas.cap.domain.SanitizationRule;
import com.envestnet.atlas.cap.ingest.DemoIngester;
import com.envestnet.atlas.cap.repo.DeploymentRepository;
import com.envestnet.atlas.cap.repo.EnvelopeRepository;
import com.envestnet.atlas.cap.repo.SanitizationRuleRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Internal API consumed by prj-service. Public-facing routes are mapped on the
 * gateway under /api/v1/projects/{id}/stages/capture/*.
 */
@RestController
@RequestMapping("/internal/capture")
public class CapController {

    private final DeploymentRepository deployments;
    private final EnvelopeRepository envelopes;
    private final SanitizationRuleRepository rules;
    private final DemoIngester demoIngester;
    private final JdbcTemplate jdbc;

    public CapController(DeploymentRepository deployments,
                         EnvelopeRepository envelopes,
                         SanitizationRuleRepository rules,
                         DemoIngester demoIngester,
                         JdbcTemplate jdbc) {
        this.deployments = deployments;
        this.envelopes = envelopes;
        this.rules = rules;
        this.demoIngester = demoIngester;
        this.jdbc = jdbc;
    }

    @PostMapping("/projects/{pid}/deploy")
    public ResponseEntity<Map<String, Object>> deploy(@PathVariable UUID pid,
                                                      @RequestBody DeployRequest req) throws IOException {
        DemoIngester.Result r = demoIngester.ingest(pid, req.sourcePath(), req.targetEnvelopes());
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "totalEnvelopes", r.total(),
                "faults", r.faults(),
                "perOperation", r.perOperation()
        ));
    }

    @PostMapping("/projects/{pid}/finalize")
    public Map<String, Object> finalizeCapture(@PathVariable UUID pid) {
        demoIngester.finalize(pid);
        return Map.of("ok", true);
    }

    @GetMapping("/projects/{pid}/status")
    public Map<String, Object> status(@PathVariable UUID pid) {
        List<Deployment> ds = deployments.findByProject(pid);
        long total = envelopes.countByProject(pid);
        long live = ds.stream().filter(d -> "live".equals(d.status())).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deployments", ds.stream().map(this::deploymentMap).toList());
        result.put("totalEnvelopes", total);
        result.put("liveDeployments", live);
        result.put("operationDistribution", operationCounts(pid));
        result.put("recentEnvelopes", envelopes.recent(pid, 25).stream()
                .map(this::envelopeMap).toList());
        result.put("sanitizationRules", rules.visibleFor(pid).stream()
                .map(this::ruleMap).toList());
        result.put("startedAt", earliestStart(ds));
        return result;
    }

    @GetMapping("/projects/{pid}/envelopes")
    public List<Map<String, Object>> envelopes(@PathVariable UUID pid,
                                               @RequestParam(defaultValue = "100") int limit) {
        return envelopes.recent(pid, Math.min(limit, 500)).stream()
                .map(this::envelopeMap).toList();
    }

    /* ---------------- deployment drill-in ---------------- */

    @GetMapping("/deployments/{did}/detail")
    public ResponseEntity<Map<String, Object>> deploymentDetail(@PathVariable UUID did) {
        Optional<Deployment> opt = deployments.findById(did);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        Deployment d = opt.get();

        long total = envelopes.countByDeployment(did);
        Map<String, Integer> byOp = new LinkedHashMap<>();
        Map<String, Integer> byDir = new LinkedHashMap<>();
        for (Envelope e : envelopes.recentForDeployment(did, 100_000)) {
            byOp.merge(e.operationName(), 1, Integer::sum);
            byDir.merge(e.direction(), 1, Integer::sum);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deployment", deploymentMap(d));
        out.put("totalEnvelopes", total);
        out.put("operationDistribution", byOp.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .map(e -> (Map<String, Object>) Map.<String, Object>of(
                        "name", e.getKey(), "count", e.getValue()))
                .toList());
        out.put("directionDistribution", byDir.entrySet().stream()
                .map(e -> (Map<String, Object>) Map.<String, Object>of(
                        "direction", e.getKey(), "count", e.getValue()))
                .toList());
        out.put("recentEnvelopes", envelopes.recentForDeployment(did, 50).stream()
                .map(this::envelopeMap).toList());
        return ResponseEntity.ok(out);
    }

    @PutMapping("/deployments/{did}")
    public ResponseEntity<Map<String, Object>> updateDeployment(
            @PathVariable UUID did,
            @RequestBody UpdateDeploymentRequest req) {
        if (deployments.findById(did).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (req.sampleRate() != null) {
            int rate = Math.max(1, Math.min(100, req.sampleRate()));
            jdbc.update("UPDATE cap.deployment SET sample_rate = ? WHERE id = ?",
                    rate, did);
        }
        Deployment updated = deployments.findById(did).orElseThrow();
        return ResponseEntity.ok(Map.of("ok", true, "deployment", deploymentMap(updated)));
    }

    @PutMapping("/sanitization-rules/{rid}")
    public ResponseEntity<Map<String, Object>> updateRule(
            @PathVariable UUID rid,
            @RequestBody UpdateRuleRequest req) {
        // Defensive: confirm the rule exists.
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM cap.sanitization_rule WHERE id = ?",
                Long.class, rid);
        if (count == null || count == 0) return ResponseEntity.notFound().build();

        if (req.enabled() != null) {
            jdbc.update("UPDATE cap.sanitization_rule SET enabled = ? WHERE id = ?",
                    req.enabled(), rid);
        }
        if (req.strategy() != null
                && Set.of("hash", "redact", "tokenize", "leave").contains(req.strategy())) {
            jdbc.update("UPDATE cap.sanitization_rule SET strategy = ? WHERE id = ?",
                    req.strategy(), rid);
        }
        return ResponseEntity.ok(Map.of("ok", true, "id", rid.toString()));
    }

    /* ---------------- helpers ---------------- */

    private List<Map<String, Object>> operationCounts(UUID pid) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Envelope e : envelopes.recent(pid, 100_000)) {
            counts.merge(e.operationName(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .map(e -> (Map<String, Object>) Map.<String, Object>of("name", e.getKey(), "count", e.getValue()))
                .toList();
    }

    private Map<String, Object> deploymentMap(Deployment d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id());
        m.put("environment", d.environment());
        m.put("method", d.method());
        m.put("status", d.status());
        m.put("sampleRate", d.sampleRate());
        m.put("startedAt", d.startedAt());
        m.put("stoppedAt", d.stoppedAt());
        return m;
    }

    private Map<String, Object> envelopeMap(Envelope e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("operation", e.operationName());
        m.put("direction", e.direction());
        m.put("partner", e.partner());
        m.put("environment", e.environment());
        m.put("size", e.sizeBytes());
        m.put("capturedAt", e.capturedAt());
        m.put("sanitizationHits", e.sanitizationHits());
        m.put("correlationId", e.correlationId());
        return m;
    }

    private Map<String, Object> ruleMap(SanitizationRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("name", r.name());
        m.put("strategy", r.strategy());
        m.put("hits", r.hits());
        m.put("enabled", r.enabled());
        m.put("global", r.projectId() == null);
        m.put("pattern", r.pattern());
        return m;
    }

    private OffsetDateTime earliestStart(List<Deployment> ds) {
        return ds.stream().map(Deployment::startedAt).filter(Objects::nonNull)
                .min(OffsetDateTime::compareTo).orElse(null);
    }

    public record DeployRequest(String sourcePath, int targetEnvelopes) {}
    public record UpdateDeploymentRequest(Integer sampleRate) {}
    public record UpdateRuleRequest(Boolean enabled, String strategy) {}
}
