package com.envestnet.atlas.uplift.service;

import com.envestnet.atlas.uplift.domain.CharCaseRow;
import com.envestnet.atlas.uplift.domain.CharRunRow;
import com.envestnet.atlas.uplift.domain.MigrationRunRow;
import com.envestnet.atlas.uplift.domain.ModuleRow;
import com.envestnet.atlas.uplift.prov.ProvenanceEmitter;
import com.envestnet.atlas.uplift.repo.CharCaseRepository;
import com.envestnet.atlas.uplift.repo.CharRunRepository;
import com.envestnet.atlas.uplift.repo.MigrationRunRepository;
import com.envestnet.atlas.uplift.repo.ModuleRepository;
import com.envestnet.atlas.uplift.repo.ScanRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Synthetic characterization-test runner. Looks at the migrations completed in
 * Stage D and, for each affected module, generates a deterministic batch of
 * test cases. Most cases pass (legacy == new). A small fraction simulate the
 * realistic divergences any framework migration emits — JSON ordering, type
 * widening, exception class swaps, enum drift — so the user has something
 * meaningful to triage.
 */
@Service
public class CharService {
    private static final Logger log = LoggerFactory.getLogger(CharService.class);

    private final CharRunRepository runs;
    private final CharCaseRepository cases;
    private final MigrationRunRepository migrationRuns;
    private final ModuleRepository modules;
    private final ScanRunRepository scanRuns;
    private final ProvenanceEmitter prov;
    private final JdbcTemplate jdbc;

    public CharService(CharRunRepository runs,
                       CharCaseRepository cases,
                       MigrationRunRepository migrationRuns,
                       ModuleRepository modules,
                       ScanRunRepository scanRuns,
                       ProvenanceEmitter prov,
                       JdbcTemplate jdbc) {
        this.runs = runs;
        this.cases = cases;
        this.migrationRuns = migrationRuns;
        this.modules = modules;
        this.scanRuns = scanRuns;
        this.prov = prov;
        this.jdbc = jdbc;
    }

    @Transactional
    public CharRunRow run(UUID projectId, int casesPerModule) {
        // Find the modules that were migrated in Stage D.
        List<MigrationRunRow> mig = migrationRuns.findByProject(projectId).stream()
                .filter(r -> "completed".equals(r.status()))
                .toList();
        if (mig.isEmpty()) {
            throw new IllegalStateException(
                "No completed module migrations yet — run Stage D first.");
        }
        // Use the most recent run per module.
        Map<UUID, MigrationRunRow> latestByModule = new LinkedHashMap<>();
        for (MigrationRunRow r : mig) {
            if (r.moduleId() == null) continue;
            latestByModule.merge(r.moduleId(), r,
                    (a, b) -> a.startedAt().isAfter(b.startedAt()) ? a : b);
        }

        UUID runId = UUID.randomUUID();
        int target = Math.max(1, Math.min(200, casesPerModule));
        jdbc.update("""
            INSERT INTO uplift.char_run (id, project_id, sample_size, status)
            VALUES (?, ?, ?, 'running')
            """, runId, projectId, target * latestByModule.size());

        Random rnd = new Random(projectId.hashCode() ^ runId.hashCode());
        int pass = 0, benign = 0, regression = 0;
        try {
            for (UUID moduleId : latestByModule.keySet()) {
                ModuleRow mod = modules.findById(moduleId).orElse(null);
                if (mod == null) continue;
                String moduleKind = inferKind(mod);
                for (int i = 0; i < target; i++) {
                    Case c = synthesize(moduleKind, mod, i, rnd);
                    jdbc.update("""
                        INSERT INTO uplift.char_case
                            (run_id, module_id, test_name, test_kind, input_summary,
                             legacy_output, new_output, bucket, diff_kind, ai_recommendation)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, runId, moduleId, c.testName, c.testKind, c.inputSummary,
                        c.legacyOutput, c.newOutput, c.bucket, c.diffKind, c.aiRecommendation);
                    switch (c.bucket) {
                        case "pass" -> pass++;
                        case "benign" -> benign++;
                        case "regression" -> regression++;
                    }
                }
            }
            jdbc.update("""
                UPDATE uplift.char_run
                   SET status = 'completed', finished_at = now(),
                       pass_count = ?, benign_count = ?, regression_count = ?
                 WHERE id = ?
                """, pass, benign, regression, runId);
        } catch (Exception e) {
            jdbc.update("""
                UPDATE uplift.char_run
                   SET status = 'failed', finished_at = now(), error_text = ?
                 WHERE id = ?
                """, e.getMessage(), runId);
            throw new RuntimeException("characterization run failed: " + e.getMessage(), e);
        }

        ProvenanceEmitter.Event ev = ProvenanceEmitter.Event.of(
                projectId, "system", "uplift-service", "characterization_run_completed");
        ev.output = Map.of("runId", runId.toString(),
                "modules", latestByModule.size(),
                "pass", pass, "benign", benign, "regression", regression);
        prov.emit(ev);

        log.info("char run done · project={} modules={} pass={} benign={} regression={}",
                projectId, latestByModule.size(), pass, benign, regression);
        return runs.findById(runId).orElseThrow();
    }

    public Map<String, Object> status(UUID projectId) {
        Optional<CharRunRow> latest = runs.latest(projectId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (latest.isEmpty()) {
            out.put("run", null);
            out.put("cases", List.of());
            out.put("byModule", List.of());
            out.put("openRegressions", 0);
            out.put("readyForGate", false);
            return out;
        }
        CharRunRow r = latest.get();
        List<CharCaseRow> rows = cases.findByRun(r.id());

        Map<UUID, ModuleRow> modMap = scanRuns.latest(projectId)
                .map(sc -> modules.findByRun(sc.id()).stream()
                        .collect(Collectors.toMap(ModuleRow::id, m -> m)))
                .orElse(Map.of());

        // Per-module counts.
        Map<UUID, int[]> byMod = new LinkedHashMap<>();
        for (CharCaseRow c : rows) {
            int[] arr = byMod.computeIfAbsent(c.moduleId(), k -> new int[3]);
            switch (c.bucket()) {
                case "pass" -> arr[0]++;
                case "benign" -> arr[1]++;
                case "regression" -> arr[2]++;
            }
        }
        List<Map<String, Object>> byModuleJson = byMod.entrySet().stream().map(e -> {
            ModuleRow m = e.getKey() == null ? null : modMap.get(e.getKey());
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("moduleId", e.getKey());
            mm.put("moduleName", m == null ? "(unknown)" : m.name());
            mm.put("packageName", m == null ? "" : m.packageName());
            mm.put("pass", e.getValue()[0]);
            mm.put("benign", e.getValue()[1]);
            mm.put("regression", e.getValue()[2]);
            return mm;
        }).toList();

        long openRegressions = rows.stream()
                .filter(c -> "regression".equals(c.bucket()))
                .filter(c -> "open".equals(c.triageState()))
                .count();

        out.put("run", runMap(r));
        out.put("byModule", byModuleJson);
        out.put("cases", rows.stream().map(this::caseMap).toList());
        out.put("openRegressions", openRegressions);
        out.put("readyForGate", r.regressionCount() != null
                && r.passCount() != null && r.passCount() > 0
                && openRegressions == 0);
        return out;
    }

    @Transactional
    public CharCaseRow triage(UUID caseId, String state, String user) {
        if (!Set.of("open", "accepted", "rejected", "fixed").contains(state)) {
            throw new IllegalArgumentException("invalid triage state: " + state);
        }
        CharCaseRow row = cases.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("case not found"));
        jdbc.update("""
            UPDATE uplift.char_case
               SET triage_state = ?, triaged_by = ?, triaged_at = now()
             WHERE id = ?
            """, state, user, caseId);
        return cases.findById(caseId).orElseThrow();
    }

    /* ---------------- synthesis ---------------- */

    /** A module's "kind" decides the flavour of test cases we generate. */
    private static String inferKind(ModuleRow m) {
        String n = m.name() == null ? "" : m.name().toLowerCase(Locale.ROOT);
        if (n.contains("web") || n.contains("controller")) return "controller";
        if (n.contains("repo") || n.contains("dao") || n.contains("persist")) return "persistence";
        if (n.contains("naming") || n.contains("jndi"))    return "jndi";
        if (n.contains("service"))                         return "service";
        return "domain";
    }

    private record Case(String testName, String testKind, String inputSummary,
                        String legacyOutput, String newOutput,
                        String bucket, String diffKind, String aiRecommendation) {}

    private static Case synthesize(String kind, ModuleRow mod, int seq, Random rnd) {
        // Roll outcome: ~85% pass, ~10% benign, ~5% regression.
        int roll = rnd.nextInt(100);
        String bucket = roll < 85 ? "pass" : roll < 95 ? "benign" : "regression";

        return switch (kind) {
            case "controller"  -> controllerCase(mod, seq, bucket, rnd);
            case "persistence" -> persistenceCase(mod, seq, bucket, rnd);
            case "jndi"        -> jndiCase(mod, seq, bucket, rnd);
            case "service"     -> serviceCase(mod, seq, bucket, rnd);
            default            -> domainCase(mod, seq, bucket, rnd);
        };
    }

    private static Case controllerCase(ModuleRow m, int seq, String bucket, Random rnd) {
        String[] verbs = { "GET", "POST", "PUT", "DELETE" };
        String[] paths = {
            "/api/orders", "/api/orders/42", "/api/orders/search?status=OPEN",
            "/api/customers/3001/orders", "/api/orders/42/status"
        };
        String verb = verbs[rnd.nextInt(verbs.length)];
        String path = paths[rnd.nextInt(paths.length)];
        String name = String.format("%s %s [%d]", verb, path, seq + 1);
        String input = String.format("HTTP %s %s", verb, path);

        if ("pass".equals(bucket)) {
            String body = "{\"id\":42,\"status\":\"OPEN\",\"total\":1250.00}";
            return new Case(name, "controller", input, body, body, "pass", "exact_match", null);
        } else if ("benign".equals(bucket)) {
            // JSON property order changed.
            String legacy = "{\"id\":42,\"status\":\"OPEN\",\"total\":1250.00}";
            String revised = "{\"status\":\"OPEN\",\"total\":1250.00,\"id\":42}";
            return new Case(name, "controller", input, legacy, revised, "benign", "ordering",
                    "Jackson default property order changed in Spring Boot 3 — accept if "
                  + "consumers parse by name.");
        }
        // regression
        String legacy = "{\"id\":42,\"status\":\"OPEN\",\"total\":1250.00}";
        String revised = "{\"id\":42,\"status\":\"OPEN\",\"total\":1250}";
        return new Case(name, "controller", input, legacy, revised, "regression", "type_drift",
                "BigDecimal serialised as integer when fractional digits are zero. Add "
              + "@JsonFormat(shape=Shape.STRING) or override the serializer.");
    }

    private static Case persistenceCase(ModuleRow m, int seq, String bucket, Random rnd) {
        String[] ops = { "save", "findById", "findByStatus", "delete" };
        String op = ops[rnd.nextInt(ops.length)];
        String name = String.format("%s.%s [%d]", m.name(), op, seq + 1);
        String input = String.format("repo.%s(Order#%d)", op, 9000 + rnd.nextInt(999));

        if ("pass".equals(bucket)) {
            return new Case(name, "persistence", input, "Order(id=9182, status=OPEN)",
                    "Order(id=9182, status=OPEN)", "pass", "exact_match", null);
        } else if ("benign".equals(bucket)) {
            return new Case(name, "persistence", input,
                    "Order(id=9182, createdAt=2025-01-15T10:30:00.123Z)",
                    "Order(id=9182, createdAt=2025-01-15T10:30:00.123456Z)",
                    "benign", "whitespace",
                    "Microsecond precision now exposed by JDBC driver upgrade. Safe — "
                  + "comparators still match.");
        }
        return new Case(name, "persistence", input,
                "javax.persistence.NoResultException",
                "java.util.NoSuchElementException",
                "regression", "exception_change",
                "Spring Data JPA 3 wraps repository misses in NoSuchElementException; "
              + "callers catching NoResultException will silently miss the case.");
    }

    private static Case jndiCase(ModuleRow m, int seq, String bucket, Random rnd) {
        String[] keys = {
            "java:comp/env/jdbc/orders",
            "java:comp/env/jms/auditQueue",
            "java:comp/env/services/orderRouter",
            "java:global/PortfolioConfig"
        };
        String key = keys[rnd.nextInt(keys.length)];
        String name = String.format("lookup %s [%d]", key, seq + 1);
        String input = String.format("ctx.lookup(\"%s\")", key);

        if ("pass".equals(bucket)) {
            return new Case(name, "jndi", input, "OK", "OK", "pass", "exact_match", null);
        } else if ("benign".equals(bucket)) {
            return new Case(name, "jndi", input,
                    "com.ibm.websphere.naming.WsnInitialContext$Wrapper",
                    "javax.naming.InitialContext", "benign", "type_drift",
                    "WebSphere wrapper class no longer present after the IbmWebSphere"
                  + "ToStandard recipe — javax.naming is the spec-correct binding.");
        }
        return new Case(name, "jndi", input,
                "Resource resolved",
                "javax.naming.NameNotFoundException: " + key,
                "regression", "missing_binding",
                "Container-managed binding present in WebSphere is missing in Tomcat. "
              + "Add a context.xml entry or migrate to @Bean configuration.");
    }

    private static Case serviceCase(ModuleRow m, int seq, String bucket, Random rnd) {
        String[] methods = { "validate", "submit", "cancel", "split" };
        String mth = methods[rnd.nextInt(methods.length)];
        String name = String.format("%s.%s [%d]", m.name(), mth, seq + 1);
        String input = String.format("OrderRequest(amount=%d.00)",
                100 + rnd.nextInt(9000));

        if ("pass".equals(bucket)) {
            return new Case(name, "service", input, "Result(ok)", "Result(ok)", "pass", "exact_match", null);
        } else if ("benign".equals(bucket)) {
            return new Case(name, "service", input, "  Result(ok) ", "Result(ok)",
                    "benign", "whitespace",
                    "Trailing whitespace stripped by the new logger pipeline. Safe.");
        }
        return new Case(name, "service", input,
                "Result(ok, warnings=[\"OBSOLETE_SKU\"])",
                "Result(ok)", "regression", "missing_warning",
                "Warning surfaced by legacy validator is no longer emitted. Likely the "
              + "warning catalog file wasn't migrated — check src/main/resources/warnings.properties.");
    }

    private static Case domainCase(ModuleRow m, int seq, String bucket, Random rnd) {
        String[] fields = { "accountId", "fundSymbol", "amount", "tradeDate" };
        String field = fields[rnd.nextInt(fields.length)];
        String name = String.format("Validation.%s [%d]", field, seq + 1);
        String input = String.format("Order{%s=null}", field);

        if ("pass".equals(bucket)) {
            return new Case(name, "domain", input,
                    "ConstraintViolation(field=" + field + ", code=NotNull)",
                    "ConstraintViolation(field=" + field + ", code=NotNull)",
                    "pass", "exact_match", null);
        } else if ("benign".equals(bucket)) {
            return new Case(name, "domain", input,
                    "javax.validation.ConstraintViolationException",
                    "jakarta.validation.ConstraintViolationException",
                    "benign", "namespace_change",
                    "Expected after the JavaxValidation→JakartaValidation recipe — "
                  + "callers using the FQN should be updated, accept as benign migration.");
        }
        return new Case(name, "domain", input,
                "ConstraintViolation(field=" + field + ", code=NotNull)",
                "(no violation)", "regression", "validator_dropped",
                "Bean Validation 3.0 ignores @NotNull on Optional fields. Either change "
              + "the field type or annotate with @Valid + @NotNull at the wrapper level.");
    }

    /* ---------------- mappers ---------------- */

    private Map<String, Object> runMap(CharRunRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("startedAt", r.startedAt());
        m.put("finishedAt", r.finishedAt());
        m.put("status", r.status());
        m.put("sampleSize", r.sampleSize());
        m.put("passCount", r.passCount());
        m.put("benignCount", r.benignCount());
        m.put("regressionCount", r.regressionCount());
        m.put("errorText", r.errorText());
        return m;
    }

    private Map<String, Object> caseMap(CharCaseRow c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("moduleId", c.moduleId());
        m.put("testName", c.testName());
        m.put("testKind", c.testKind());
        m.put("inputSummary", c.inputSummary());
        m.put("legacyOutput", c.legacyOutput());
        m.put("newOutput", c.newOutput());
        m.put("bucket", c.bucket());
        m.put("diffKind", c.diffKind());
        m.put("aiRecommendation", c.aiRecommendation());
        m.put("triageState", c.triageState());
        m.put("triagedBy", c.triagedBy());
        m.put("triagedAt", c.triagedAt());
        return m;
    }
}
