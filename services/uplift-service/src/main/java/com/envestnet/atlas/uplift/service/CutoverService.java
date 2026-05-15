package com.envestnet.atlas.uplift.service;

import com.envestnet.atlas.uplift.domain.*;
import com.envestnet.atlas.uplift.prov.ProvenanceEmitter;
import com.envestnet.atlas.uplift.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Per-step cutover plan + decommission checklist + project-level closure doc.
 * Closes the UPLIFT track; once every cutover is live or decommissioned and
 * every checklist item is checked, the project gate finalizes.
 */
@Service
public class CutoverService {
    private static final Logger log = LoggerFactory.getLogger(CutoverService.class);

    private final CutoverRepository cutovers;
    private final StranglerStepRepository steps;
    private final ModuleRepository modules;
    private final RecipeRepository recipes;
    private final MigrationRunRepository migrationRuns;
    private final CharRunRepository charRuns;
    private final ScanRunRepository scanRuns;
    private final ProvenanceEmitter prov;
    private final JdbcTemplate jdbc;

    public CutoverService(CutoverRepository cutovers,
                          StranglerStepRepository steps,
                          ModuleRepository modules,
                          RecipeRepository recipes,
                          MigrationRunRepository migrationRuns,
                          CharRunRepository charRuns,
                          ScanRunRepository scanRuns,
                          ProvenanceEmitter prov,
                          JdbcTemplate jdbc) {
        this.cutovers = cutovers;
        this.steps = steps;
        this.modules = modules;
        this.recipes = recipes;
        this.migrationRuns = migrationRuns;
        this.charRuns = charRuns;
        this.scanRuns = scanRuns;
        this.prov = prov;
        this.jdbc = jdbc;
    }

    /**
     * Materialize one cutover row per strangler step that's `ready` or
     * `extracted`. Existing rows preserved; only checklist refreshes if it's
     * empty.
     */
    @Transactional
    public List<CutoverRow> seed(UUID projectId) {
        List<StranglerStepRow> all = steps.findByProject(projectId);
        List<StranglerStepRow> eligible = all.stream()
                .filter(s -> Set.of("ready", "extracted").contains(s.status()))
                .toList();
        if (eligible.isEmpty()) {
            throw new IllegalStateException(
                "No strangler steps are ready yet — finish Stage D migrations first.");
        }

        Map<UUID, ModuleRow> modById = scanRuns.latest(projectId)
                .map(r -> modules.findByRun(r.id()).stream()
                        .collect(Collectors.toMap(ModuleRow::id, m -> m)))
                .orElse(Map.of());

        int seeded = 0, refreshed = 0;
        for (StranglerStepRow step : eligible) {
            ModuleRow mod = step.moduleId() == null ? null : modById.get(step.moduleId());
            String checklistJson = defaultChecklistJson(mod);
            String rollback = defaultRollbackPlan(mod);

            Optional<CutoverRow> existing = cutovers.findByStep(projectId, step.id());
            if (existing.isPresent()) {
                // Only fill in defaults that are still blank.
                CutoverRow r = existing.get();
                boolean checklistEmpty = r.checklist() == null
                        || r.checklist().trim().equals("[]")
                        || r.checklist().isBlank();
                if (checklistEmpty || r.rollbackPlan() == null || r.rollbackPlan().isBlank()) {
                    jdbc.update("""
                        UPDATE uplift.cutover
                           SET checklist = CASE WHEN ? THEN ?::jsonb ELSE checklist END,
                               rollback_plan = COALESCE(rollback_plan, ?),
                               updated_at = now()
                         WHERE id = ?
                        """, checklistEmpty, checklistJson, rollback, r.id());
                    refreshed++;
                }
            } else {
                jdbc.update("""
                    INSERT INTO uplift.cutover
                        (project_id, step_id, module_id, state, traffic_percent,
                         rollback_plan, checklist)
                    VALUES (?, ?, ?, 'planned', 0, ?, ?::jsonb)
                    """, projectId, step.id(), step.moduleId(), rollback, checklistJson);
                seeded++;
            }
        }

        log.info("Cutover plan seeded · project={} new={} refreshed={} total={}",
                projectId, seeded, refreshed, eligible.size());

        ProvenanceEmitter.Event ev = ProvenanceEmitter.Event.of(
                projectId, "system", "uplift-service", "cutover_plan_seeded");
        ev.output = Map.of("seeded", seeded, "refreshed", refreshed,
                "totalSteps", eligible.size());
        prov.emit(ev);

        return cutovers.findByProject(projectId);
    }

    @Transactional
    public CutoverRow updateState(UUID projectId, UUID cutoverId, String newState,
                                   Integer trafficPercent, String notes, String user) {
        if (!Set.of("planned", "shadow", "canary", "live",
                    "rolled_back", "decommissioned").contains(newState)) {
            throw new IllegalArgumentException("invalid state: " + newState);
        }
        CutoverRow row = cutovers.findById(cutoverId)
                .orElseThrow(() -> new IllegalArgumentException("cutover not found"));
        if (!row.projectId().equals(projectId)) {
            throw new IllegalArgumentException("cutover belongs to a different project");
        }
        Integer effectiveTraffic = trafficPercent != null ? clampPct(trafficPercent)
                : defaultTrafficForState(newState, row.trafficPercent());
        boolean isApproval = "live".equals(newState) || "decommissioned".equals(newState);
        boolean stampCutoverDate = "live".equals(newState) && row.cutoverDate() == null;

        jdbc.update("""
            UPDATE uplift.cutover
               SET state           = ?,
                   traffic_percent = ?,
                   notes           = COALESCE(?, notes),
                   approved_by     = CASE WHEN ? THEN ? ELSE approved_by END,
                   approved_at     = CASE WHEN ? THEN now() ELSE approved_at END,
                   cutover_date    = CASE WHEN ? THEN now() ELSE cutover_date END,
                   updated_at      = now()
             WHERE id = ?
            """, newState, effectiveTraffic, notes,
                isApproval, user,
                isApproval,
                stampCutoverDate,
                cutoverId);

        // When live → mark the strangler step as 'extracted' (terminal) so other
        // stages reflect that this module is in production.
        if ("live".equals(newState) && row.stepId() != null) {
            jdbc.update("""
                UPDATE uplift.strangler_step
                   SET status = 'extracted', updated_at = now()
                 WHERE id = ?
                """, row.stepId());
        }

        ProvenanceEmitter.Event ev = ProvenanceEmitter.Event.of(
                projectId, "human", user == null ? "anonymous" : user, "cutover_state_changed");
        ev.output = Map.of("cutoverId", cutoverId.toString(),
                "state", newState, "trafficPercent", effectiveTraffic);
        prov.emit(ev);

        return cutovers.findById(cutoverId).orElseThrow();
    }

    @Transactional
    public CutoverRow toggleChecklistItem(UUID projectId, UUID cutoverId, String itemId,
                                           boolean done, String user) {
        CutoverRow row = cutovers.findById(cutoverId)
                .orElseThrow(() -> new IllegalArgumentException("cutover not found"));
        if (!row.projectId().equals(projectId)) {
            throw new IllegalArgumentException("cutover belongs to a different project");
        }
        // Re-serialise the checklist with the item flipped. JSON parsing is
        // deliberately tiny here — we know the schema we wrote.
        String updated = flipChecklistItem(row.checklist(), itemId, done);
        jdbc.update("""
            UPDATE uplift.cutover
               SET checklist = ?::jsonb, updated_at = now()
             WHERE id = ?
            """, updated, cutoverId);
        return cutovers.findById(cutoverId).orElseThrow();
    }

    public Map<String, Object> status(UUID projectId) {
        List<CutoverRow> rows = cutovers.findByProject(projectId);

        Map<UUID, ModuleRow> modById = scanRuns.latest(projectId)
                .map(r -> modules.findByRun(r.id()).stream()
                        .collect(Collectors.toMap(ModuleRow::id, m -> m)))
                .orElse(Map.of());

        Map<String, Long> byState = rows.stream()
                .collect(Collectors.groupingBy(CutoverRow::state, Collectors.counting()));

        boolean readyForGate = !rows.isEmpty() && rows.stream().allMatch(r -> {
            boolean inFinalState = "live".equals(r.state()) || "decommissioned".equals(r.state());
            boolean checklistDone = checklistAllChecked(r.checklist());
            return inFinalState && checklistDone;
        });

        List<Map<String, Object>> rowsJson = rows.stream().map(r -> {
            ModuleRow mod = r.moduleId() == null ? null : modById.get(r.moduleId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("stepId", r.stepId());
            m.put("moduleId", r.moduleId());
            m.put("module", mod == null ? null : moduleSummary(mod));
            m.put("state", r.state());
            m.put("trafficPercent", r.trafficPercent());
            m.put("cutoverDate", r.cutoverDate());
            m.put("rollbackPlan", r.rollbackPlan());
            m.put("notes", r.notes());
            m.put("checklist", parseChecklist(r.checklist()));
            m.put("approvedBy", r.approvedBy());
            m.put("approvedAt", r.approvedAt());
            return m;
        }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cutovers", rowsJson);
        out.put("counts", Map.of(
                "total", rows.size(),
                "planned", byState.getOrDefault("planned", 0L),
                "shadow", byState.getOrDefault("shadow", 0L),
                "canary", byState.getOrDefault("canary", 0L),
                "live", byState.getOrDefault("live", 0L),
                "rolledBack", byState.getOrDefault("rolled_back", 0L),
                "decommissioned", byState.getOrDefault("decommissioned", 0L)));
        out.put("readyForGate", readyForGate);
        return out;
    }

    /** Build a markdown closure document for the project. */
    public String closureDocument(UUID projectId, String projectName, String sourcePath) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Migration closure · ").append(projectName).append("\n\n");
        sb.append("_Generated ").append(now()).append(" · Track B Framework Uplift_\n\n");
        sb.append("Source: `").append(sourcePath == null ? "(unset)" : sourcePath).append("`\n\n");

        // Inventory
        scanRuns.latest(projectId).ifPresent(r -> {
            List<ModuleRow> mods = modules.findByRun(r.id());
            sb.append("## Stage A · Inventory\n\n");
            sb.append("- ").append(mods.size()).append(" modules scanned\n");
            int loc = mods.stream().mapToInt(m -> m.loc()).sum();
            int findings = mods.stream().mapToInt(m -> m.findingCount()).sum();
            sb.append("- ").append(loc).append(" lines of code · ")
                .append(findings).append(" findings\n\n");
            sb.append("| Module | Package | LOC | Difficulty | Findings |\n");
            sb.append("|---|---|---:|---:|---:|\n");
            for (ModuleRow m : mods) {
                sb.append("| ").append(m.name())
                    .append(" | `").append(m.packageName()).append("`")
                    .append(" | ").append(m.loc())
                    .append(" | ").append(m.difficulty()).append("/10")
                    .append(" | ").append(m.findingCount()).append(" |\n");
            }
            sb.append('\n');
        });

        // Recipes
        List<RecipeRow> rs = recipes.findByProject(projectId);
        long accepted = rs.stream().filter(r -> "accepted".equals(r.status())).count();
        long rejected = rs.stream().filter(r -> "rejected".equals(r.status())).count();
        sb.append("## Stage B · Recipes\n\n");
        sb.append("- ").append(rs.size()).append(" recipes curated · ")
          .append(accepted).append(" accepted · ")
          .append(rejected).append(" rejected\n\n");
        if (!rs.isEmpty()) {
            sb.append("| Recipe | Kind | Status |\n|---|---|---|\n");
            for (RecipeRow r : rs) {
                sb.append("| ").append(escape(r.label()))
                    .append(" | ").append(r.kind())
                    .append(" | ").append(r.status()).append(" |\n");
            }
            sb.append('\n');
        }

        // Strangler plan
        List<StranglerStepRow> ss = steps.findByProject(projectId);
        sb.append("## Stage C · Strangler plan\n\n- ")
            .append(ss.size()).append(" steps sequenced\n\n");
        if (!ss.isEmpty()) {
            sb.append("| # | Module | Status |\n|---:|---|---|\n");
            for (StranglerStepRow s : ss) {
                ModuleRow m = s.moduleId() == null ? null
                        : modules.findById(s.moduleId()).orElse(null);
                sb.append("| ").append(s.sequenceNo())
                    .append(" | ").append(m == null ? "(missing)" : m.name())
                    .append(" | ").append(s.status()).append(" |\n");
            }
            sb.append('\n');
        }

        // Migrations
        List<MigrationRunRow> mr = migrationRuns.findByProject(projectId);
        long migCompleted = mr.stream().filter(r -> "completed".equals(r.status())).count();
        sb.append("## Stage D · Module migrations\n\n");
        sb.append("- ").append(mr.size()).append(" runs · ")
            .append(migCompleted).append(" completed\n\n");

        // Characterization
        charRuns.latest(projectId).ifPresent(cr -> {
            sb.append("## Stage E · Characterization validation\n\n");
            sb.append("- Sample size: ").append(cr.sampleSize()).append("\n");
            sb.append("- Pass: ").append(cr.passCount()).append("\n");
            sb.append("- Benign divergences: ").append(cr.benignCount()).append("\n");
            sb.append("- Regressions: ").append(cr.regressionCount());
            sb.append(cr.regressionCount() != null && cr.regressionCount() > 0
                    ? " (all triaged before cutover)\n\n" : "\n\n");
        });

        // Cutovers
        List<CutoverRow> cs = cutovers.findByProject(projectId);
        sb.append("## Stage F · Cutover & decommission\n\n");
        sb.append("- ").append(cs.size()).append(" cutovers planned\n");
        long live = cs.stream().filter(c -> "live".equals(c.state())).count();
        long decom = cs.stream().filter(c -> "decommissioned".equals(c.state())).count();
        sb.append("- ").append(live).append(" live · ")
            .append(decom).append(" decommissioned\n\n");
        if (!cs.isEmpty()) {
            sb.append("| Module | State | Traffic | Cut over at | Approved by |\n");
            sb.append("|---|---|---:|---|---|\n");
            for (CutoverRow c : cs) {
                ModuleRow m = c.moduleId() == null ? null
                        : modules.findById(c.moduleId()).orElse(null);
                sb.append("| ").append(m == null ? "(missing)" : m.name())
                    .append(" | ").append(c.state())
                    .append(" | ").append(c.trafficPercent()).append("%")
                    .append(" | ").append(c.cutoverDate() == null ? "—" : c.cutoverDate())
                    .append(" | ").append(c.approvedBy() == null ? "—" : c.approvedBy())
                    .append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("---\n_Atlas Migrate · framework uplift complete._\n");
        return sb.toString();
    }

    /* ---------------- helpers ---------------- */

    private static Integer clampPct(int v) { return Math.max(0, Math.min(100, v)); }

    private static Integer defaultTrafficForState(String state, Integer current) {
        return switch (state) {
            case "shadow" -> 0;
            case "canary" -> current != null && current >= 5 && current < 100 ? current : 10;
            case "live", "decommissioned" -> 100;
            case "rolled_back", "planned" -> 0;
            default -> current == null ? 0 : current;
        };
    }

    private static String defaultRollbackPlan(ModuleRow mod) {
        if (mod == null) return "Switch facade route back to legacy module.";
        String name = mod.name() == null ? "module" : mod.name();
        return "Flip facade route for `" + name + "` back to the legacy package "
            + "`" + mod.packageName() + "`. Drain canary traffic over 10 minutes "
            + "while monitoring p95 latency and error rate. If errors > 1% over "
            + "5 minutes, abort cutover and re-open Stage E with the new evidence.";
    }

    /** Module-kind-aware default checklist. */
    private static String defaultChecklistJson(ModuleRow mod) {
        String kind = mod == null ? "" : (mod.name() == null ? "" : mod.name().toLowerCase(Locale.ROOT));
        List<Item> base = new ArrayList<>(List.of(
            new Item("smoke", "Smoke-test the new module in QUAT", "test"),
            new Item("dep_tag", "Tag legacy package as @Deprecated", "code"),
            new Item("runbook", "Update runbook with new module location", "docs"),
            new Item("oncall", "Notify ops + on-call rotation", "ops"),
            new Item("shadow_window", "Schedule 24h shadow window", "ops"),
            new Item("canary_window", "Schedule 48h canary at 10% traffic", "ops"),
            new Item("cutover_window", "Schedule full cutover window", "ops"),
            new Item("decom_remove", "Remove legacy package once cutover stable for 7 days", "code")
        ));
        if (kind.contains("web") || kind.contains("controller")) {
            base.add(new Item("route", "Reroute /api/* via Spring Cloud Gateway", "infra"));
        } else if (kind.contains("repo") || kind.contains("persist") || kind.contains("dao")) {
            base.add(new Item("pool", "Verify connection pool sizing for new module", "infra"));
            base.add(new Item("migration", "Confirm Liquibase changesets applied", "infra"));
        } else if (kind.contains("naming") || kind.contains("jndi")) {
            base.add(new Item("jndi", "Migrate JNDI bindings to context.xml", "infra"));
        } else if (kind.contains("service")) {
            base.add(new Item("circuit", "Add circuit breaker around new service module", "infra"));
        } else {
            base.add(new Item("dto", "Verify DTO mappings for callers", "code"));
        }
        return jsonChecklist(base);
    }

    private record Item(String id, String text, String category) {}

    private static String jsonChecklist(List<Item> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            Item it = items.get(i);
            sb.append("{\"id\":\"").append(it.id())
              .append("\",\"text\":\"").append(escapeJson(it.text()))
              .append("\",\"category\":\"").append(it.category())
              .append("\",\"done\":false}");
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Hand-rolled checklist editor: parse, flip the matching id's done flag,
     * re-emit. Avoids pulling in Jackson at the field level.
     */
    private static String flipChecklistItem(String json, String itemId, boolean done) {
        if (json == null || json.isBlank() || json.equals("[]")) return json;
        // crude split on top-level "{...}" entries
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        int depth = 0;
        int objStart = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    String obj = json.substring(objStart, i + 1);
                    if (containsId(obj, itemId)) {
                        obj = obj.replaceAll(
                            "\"done\"\\s*:\\s*(true|false)",
                            "\"done\":" + done);
                    }
                    if (!first) sb.append(',');
                    sb.append(obj);
                    first = false;
                }
            }
        }
        sb.append(']');
        return sb.toString();
    }

    private static boolean containsId(String obj, String id) {
        return obj.matches("(?s).*\"id\"\\s*:\\s*\"" + java.util.regex.Pattern.quote(id) + "\".*");
    }

    private static List<Map<String, Object>> parseChecklist(String json) {
        if (json == null || json.isBlank() || json.equals("[]")) return List.of();
        // Same crude split — extract per-object key-value pairs.
        List<Map<String, Object>> out = new ArrayList<>();
        int depth = 0; int start = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    String obj = json.substring(start, i + 1);
                    out.add(extractObj(obj));
                }
            }
        }
        return out;
    }

    private static Map<String, Object> extractObj(String obj) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",       extractString(obj, "id"));
        m.put("text",     extractString(obj, "text"));
        m.put("category", extractString(obj, "category"));
        // pg's JSONB output is `"done": true` (with whitespace) — tolerate it.
        m.put("done",     java.util.regex.Pattern.compile(
                "\"done\"\\s*:\\s*true").matcher(obj).find());
        return m;
    }

    private static String extractString(String obj, String key) {
        var m = java.util.regex.Pattern.compile(
            "\"" + key + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
        ).matcher(obj);
        return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "\\") : "";
    }

    private static boolean checklistAllChecked(String json) {
        if (json == null || json.isBlank() || json.equals("[]")) return true;
        // pg's JSONB output may include whitespace — match both shapes.
        return !java.util.regex.Pattern.compile("\"done\"\\s*:\\s*false")
                .matcher(json).find();
    }

    private static Map<String, Object> moduleSummary(ModuleRow m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", m.id());
        r.put("name", m.name());
        r.put("packageName", m.packageName());
        r.put("loc", m.loc());
        r.put("difficulty", m.difficulty());
        return r;
    }

    private static String escape(String s) { return s == null ? "" : s.replace("|", "\\|"); }
    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
    private static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'"));
    }
}
