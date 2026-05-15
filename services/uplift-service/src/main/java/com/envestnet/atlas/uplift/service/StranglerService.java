package com.envestnet.atlas.uplift.service;

import com.envestnet.atlas.uplift.domain.ModuleRow;
import com.envestnet.atlas.uplift.domain.RecipeRow;
import com.envestnet.atlas.uplift.domain.StranglerStepRow;
import com.envestnet.atlas.uplift.prov.ProvenanceEmitter;
import com.envestnet.atlas.uplift.repo.ModuleRepository;
import com.envestnet.atlas.uplift.repo.RecipeRepository;
import com.envestnet.atlas.uplift.repo.ScanRunRepository;
import com.envestnet.atlas.uplift.repo.StranglerStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class StranglerService {
    private static final Logger log = LoggerFactory.getLogger(StranglerService.class);

    private final StranglerStepRepository steps;
    private final RecipeRepository recipes;
    private final ModuleRepository modules;
    private final ScanRunRepository runs;
    private final ProvenanceEmitter prov;
    private final JdbcTemplate jdbc;

    public StranglerService(StranglerStepRepository steps,
                            RecipeRepository recipes,
                            ModuleRepository modules,
                            ScanRunRepository runs,
                            ProvenanceEmitter prov,
                            JdbcTemplate jdbc) {
        this.steps = steps;
        this.recipes = recipes;
        this.modules = modules;
        this.runs = runs;
        this.prov = prov;
        this.jdbc = jdbc;
    }

    /**
     * Build a strangler plan: one step per module that has at least one accepted
     * recipe targeting it, ordered by descending difficulty (hardest first so it
     * gets attention earliest in the program). Existing rows preserved — only
     * new modules get a step appended.
     */
    @Transactional
    public List<StranglerStepRow> seed(UUID projectId) {
        List<RecipeRow> all = recipes.findByProject(projectId);
        List<RecipeRow> accepted = all.stream()
                .filter(r -> "accepted".equals(r.status()))
                .toList();
        if (accepted.isEmpty()) {
            throw new IllegalStateException(
                "Accept at least one recipe in Stage B before seeding the strangler plan.");
        }

        // Inventory modules (so we can sort by difficulty).
        Map<UUID, ModuleRow> modById = runs.latest(projectId)
                .map(r -> modules.findByRun(r.id()).stream()
                        .collect(Collectors.toMap(ModuleRow::id, m -> m)))
                .orElse(Map.of());

        // Group accepted recipes by their target module.
        Map<UUID, List<RecipeRow>> byModule = new LinkedHashMap<>();
        for (RecipeRow r : accepted) {
            for (String moduleIdStr : parseUuidList(r.moduleIds())) {
                UUID mid = UUID.fromString(moduleIdStr);
                byModule.computeIfAbsent(mid, k -> new ArrayList<>()).add(r);
            }
        }
        if (byModule.isEmpty()) {
            throw new IllegalStateException(
                "No accepted recipes target any specific module yet.");
        }

        // Order: descending difficulty, then descending finding count.
        List<UUID> ordered = byModule.keySet().stream()
                .sorted((a, b) -> {
                    ModuleRow ma = modById.get(a);
                    ModuleRow mb = modById.get(b);
                    int da = ma == null ? 0 : ma.difficulty();
                    int db = mb == null ? 0 : mb.difficulty();
                    if (da != db) return Integer.compare(db, da);
                    int fa = ma == null ? 0 : ma.findingCount();
                    int fb = mb == null ? 0 : mb.findingCount();
                    return Integer.compare(fb, fa);
                })
                .toList();

        // Determine starting sequence number — append after existing steps.
        int seq = steps.findByProject(projectId).stream()
                .mapToInt(StranglerStepRow::sequenceNo).max().orElse(0);

        int seeded = 0, refreshed = 0;
        for (UUID mid : ordered) {
            List<RecipeRow> rs = byModule.get(mid);
            String recipeIdsJson = jsonStringArray(rs.stream()
                    .map(r -> r.id().toString()).toList());

            Optional<StranglerStepRow> existing = steps.findByModule(projectId, mid);
            if (existing.isPresent()) {
                jdbc.update("""
                    UPDATE uplift.strangler_step
                       SET recipe_ids = ?::jsonb,
                           updated_at = now()
                     WHERE id = ?
                    """, recipeIdsJson, existing.get().id());
                refreshed++;
            } else {
                seq++;
                jdbc.update("""
                    INSERT INTO uplift.strangler_step
                        (project_id, module_id, sequence_no, status, recipe_ids)
                    VALUES (?, ?, ?, 'planned', ?::jsonb)
                    """, projectId, mid, seq, recipeIdsJson);
                seeded++;
            }
        }

        log.info("Strangler plan seed · project={} new={} refreshed={} total={}",
                projectId, seeded, refreshed, byModule.size());

        ProvenanceEmitter.Event ev = ProvenanceEmitter.Event.of(
                projectId, "system", "uplift-service", "strangler_plan_seeded");
        ev.output = Map.of("seeded", seeded, "refreshed", refreshed,
                "totalSteps", byModule.size());
        prov.emit(ev);

        return steps.findByProject(projectId);
    }

    @Transactional
    public StranglerStepRow updateStep(UUID projectId, UUID stepId,
                                       String status, String facadeNotes, String user) {
        StranglerStepRow row = steps.findById(stepId)
                .orElseThrow(() -> new IllegalArgumentException("step not found"));
        if (!row.projectId().equals(projectId)) {
            throw new IllegalArgumentException("step belongs to a different project");
        }
        if (status != null && !Set.of("planned", "ready", "extracted").contains(status)) {
            throw new IllegalArgumentException("invalid status: " + status);
        }
        jdbc.update("""
            UPDATE uplift.strangler_step
               SET status        = COALESCE(?, status),
                   facade_notes  = COALESCE(?, facade_notes),
                   decided_by    = ?,
                   decided_at    = now(),
                   updated_at    = now()
             WHERE id = ?
            """, status, facadeNotes, user, stepId);

        ProvenanceEmitter.Event ev = ProvenanceEmitter.Event.of(
                projectId, "human", user == null ? "anonymous" : user, "strangler_step_updated");
        ev.output = Map.of("stepId", stepId.toString(),
                "status", status == null ? "" : status,
                "hasFacadeNotes", facadeNotes != null);
        prov.emit(ev);

        return steps.findById(stepId).orElseThrow();
    }

    /** Move a step up (delta < 0) or down (delta > 0) in the sequence. */
    @Transactional
    public void reorder(UUID projectId, UUID stepId, int delta) {
        if (delta == 0) return;
        List<StranglerStepRow> ordered = steps.findByProject(projectId);
        int idx = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).id().equals(stepId)) { idx = i; break; }
        }
        if (idx < 0) throw new IllegalArgumentException("step not in project plan");
        int target = Math.max(0, Math.min(ordered.size() - 1, idx + delta));
        if (target == idx) return;

        // Swap with neighbour by re-numbering all rows linearly. Simpler than
        // a min/max swap because we already have the full ordered list.
        Collections.swap(ordered, idx, target);
        for (int i = 0; i < ordered.size(); i++) {
            jdbc.update(
                "UPDATE uplift.strangler_step SET sequence_no = ?, updated_at = now() WHERE id = ?",
                i + 1, ordered.get(i).id());
        }
    }

    @Transactional
    public void delete(UUID projectId, UUID stepId) {
        StranglerStepRow row = steps.findById(stepId)
                .orElseThrow(() -> new IllegalArgumentException("step not found"));
        if (!row.projectId().equals(projectId)) {
            throw new IllegalArgumentException("step belongs to a different project");
        }
        jdbc.update("DELETE FROM uplift.strangler_step WHERE id = ?", stepId);
        // Compact sequence numbers so they stay 1..N with no gaps.
        List<StranglerStepRow> remaining = steps.findByProject(projectId);
        for (int i = 0; i < remaining.size(); i++) {
            jdbc.update("UPDATE uplift.strangler_step SET sequence_no = ? WHERE id = ?",
                    i + 1, remaining.get(i).id());
        }
    }

    public Map<String, Object> status(UUID projectId) {
        List<StranglerStepRow> rows = steps.findByProject(projectId);

        Map<UUID, ModuleRow> modById = runs.latest(projectId)
                .map(r -> modules.findByRun(r.id()).stream()
                        .collect(Collectors.toMap(ModuleRow::id, m -> m)))
                .orElse(Map.of());
        Map<UUID, RecipeRow> recipeById = recipes.findByProject(projectId).stream()
                .collect(Collectors.toMap(RecipeRow::id, r -> r));

        List<Map<String, Object>> stepJson = rows.stream().map(s -> {
            ModuleRow mod = s.moduleId() == null ? null : modById.get(s.moduleId());
            List<Map<String, Object>> recipeRefs = parseUuidList(s.recipeIds()).stream()
                    .map(UUID::fromString)
                    .map(recipeById::get)
                    .filter(Objects::nonNull)
                    .map(r -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", r.id());
                        m.put("recipeId", r.recipeId());
                        m.put("label", r.label());
                        m.put("kind", r.kind());
                        return m;
                    })
                    .toList();

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("sequenceNo", s.sequenceNo());
            m.put("moduleId", s.moduleId());
            m.put("module", mod == null ? null : moduleSummary(mod));
            m.put("status", s.status());
            m.put("facadeNotes", s.facadeNotes());
            m.put("recipes", recipeRefs);
            m.put("decidedBy", s.decidedBy());
            m.put("decidedAt", s.decidedAt());
            m.put("createdAt", s.createdAt());
            return m;
        }).toList();

        long ready     = rows.stream().filter(r -> "ready".equals(r.status())).count();
        long extracted = rows.stream().filter(r -> "extracted".equals(r.status())).count();
        long planned   = rows.stream().filter(r -> "planned".equals(r.status())).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("steps", stepJson);
        out.put("counts", Map.of(
                "total", rows.size(),
                "planned", planned,
                "ready", ready,
                "extracted", extracted));
        out.put("readyForGate", rows.size() > 0 && (ready + extracted) >= 1);
        return out;
    }

    /* ---------------- helpers ---------------- */

    private static Map<String, Object> moduleSummary(ModuleRow m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", m.id());
        r.put("name", m.name());
        r.put("packageName", m.packageName());
        r.put("fileCount", m.fileCount());
        r.put("loc", m.loc());
        r.put("difficulty", m.difficulty());
        r.put("findingCount", m.findingCount());
        return r;
    }

    private static String jsonStringArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values.get(i).replace("\"", "\\\"")).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static List<String> parseUuidList(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String tok : json.replaceAll("[\\[\\]\\s]", "").split(",")) {
            String s = tok.replace("\"", "");
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
