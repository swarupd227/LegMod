package com.envestnet.atlas.uplift.service;

import com.envestnet.atlas.uplift.domain.FindingRow;
import com.envestnet.atlas.uplift.domain.ModuleRow;
import com.envestnet.atlas.uplift.domain.RecipeRow;
import com.envestnet.atlas.uplift.prov.ProvenanceEmitter;
import com.envestnet.atlas.uplift.repo.FindingRepository;
import com.envestnet.atlas.uplift.repo.ModuleRepository;
import com.envestnet.atlas.uplift.repo.RecipeRepository;
import com.envestnet.atlas.uplift.repo.ScanRunRepository;
import com.envestnet.atlas.uplift.domain.ScanRunRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RecipeService {
    private static final Logger log = LoggerFactory.getLogger(RecipeService.class);

    private final RecipeRepository recipes;
    private final FindingRepository findings;
    private final ModuleRepository modules;
    private final ScanRunRepository runs;
    private final ProvenanceEmitter prov;
    private final JdbcTemplate jdbc;

    public RecipeService(RecipeRepository recipes,
                         FindingRepository findings,
                         ModuleRepository modules,
                         ScanRunRepository runs,
                         ProvenanceEmitter prov,
                         JdbcTemplate jdbc) {
        this.recipes = recipes;
        this.findings = findings;
        this.modules = modules;
        this.runs = runs;
        this.prov = prov;
        this.jdbc = jdbc;
    }

    /**
     * Idempotent: rebuilds the proposed-recipe set from the latest scan. Existing
     * accepted/rejected decisions are kept; their finding/module snapshots refresh.
     */
    @Transactional
    public List<RecipeRow> seedFromFindings(UUID projectId) {
        Optional<ScanRunRow> latest = runs.latest(projectId);
        if (latest.isEmpty()) {
            throw new IllegalStateException("No inventory scan yet — run Stage A first.");
        }
        UUID runId = latest.get().id();

        List<FindingRow> all = findings.findByRun(runId);
        if (all.isEmpty()) {
            log.info("No findings to seed recipes from · project={}", projectId);
            return List.of();
        }

        // Group findings by suggested recipe (normalized).
        Map<String, List<FindingRow>> byRecipe = all.stream()
                .filter(f -> f.suggestedRecipe() != null && !f.suggestedRecipe().isBlank())
                .collect(Collectors.groupingBy(f -> RecipeCatalog.normalize(f.suggestedRecipe()),
                        LinkedHashMap::new, Collectors.toList()));

        int seeded = 0, refreshed = 0;
        for (var entry : byRecipe.entrySet()) {
            String recipeId = entry.getKey();
            List<FindingRow> fs = entry.getValue();
            String findingIdsJson = jsonArray(fs.stream().map(f -> f.id().toString()).toList());
            String moduleIdsJson  = jsonArray(fs.stream()
                    .map(f -> f.moduleId() == null ? null : f.moduleId().toString())
                    .filter(Objects::nonNull).distinct().toList());

            Optional<RecipeRow> existing = recipes.findOne(projectId, recipeId);
            if (existing.isPresent()) {
                // Refresh finding/module snapshot; preserve status and notes.
                jdbc.update("""
                    UPDATE uplift.recipe
                       SET finding_ids = ?::jsonb,
                           module_ids  = ?::jsonb,
                           updated_at  = now()
                     WHERE id = ?
                    """, findingIdsJson, moduleIdsJson, existing.get().id());
                refreshed++;
            } else {
                RecipeCatalog.Entry meta = RecipeCatalog.lookup(recipeId);
                jdbc.update("""
                    INSERT INTO uplift.recipe
                        (project_id, recipe_id, label, description, kind, status,
                         finding_ids, module_ids)
                    VALUES (?, ?, ?, ?, ?, 'proposed', ?::jsonb, ?::jsonb)
                    """, projectId, recipeId, meta.label(), meta.description(), meta.kind(),
                        findingIdsJson, moduleIdsJson);
                seeded++;
            }
        }

        log.info("Recipe seed complete · project={} seeded={} refreshed={} total={}",
                projectId, seeded, refreshed, byRecipe.size());

        ProvenanceEmitter.Event ev = ProvenanceEmitter.Event.of(
                projectId, "system", "uplift-service", "recipes_seeded");
        ev.output = Map.of("seeded", seeded, "refreshed", refreshed,
                "totalDistinct", byRecipe.size());
        prov.emit(ev);

        return recipes.findByProject(projectId);
    }

    @Transactional
    public RecipeRow decide(UUID projectId, UUID recipeId,
                            String status, String notes, String user) {
        if (!Set.of("proposed", "accepted", "rejected").contains(status)) {
            throw new IllegalArgumentException("invalid status: " + status);
        }
        RecipeRow row = recipes.findById(recipeId)
                .orElseThrow(() -> new IllegalArgumentException("recipe not found"));
        if (!row.projectId().equals(projectId)) {
            throw new IllegalArgumentException("recipe belongs to a different project");
        }
        jdbc.update("""
            UPDATE uplift.recipe
               SET status = ?,
                   notes  = COALESCE(?, notes),
                   decided_by = ?,
                   decided_at = now(),
                   updated_at = now()
             WHERE id = ?
            """, status, notes, user, recipeId);

        ProvenanceEmitter.Event ev = ProvenanceEmitter.Event.of(
                projectId, "human", user == null ? "anonymous" : user, "recipe_decision");
        ev.output = Map.of("recipeId", row.recipeId(), "status", status);
        ev.links = List.of("recipe:" + recipeId);
        prov.emit(ev);

        return recipes.findById(recipeId).orElseThrow();
    }

    @Transactional
    public RecipeRow createCustom(UUID projectId, String recipeId, String label,
                                  String description, String notes, String user) {
        if (recipeId == null || recipeId.isBlank() || label == null || label.isBlank()) {
            throw new IllegalArgumentException("recipeId and label are required");
        }
        recipes.findOne(projectId, recipeId).ifPresent(r -> {
            throw new IllegalArgumentException("recipe already exists: " + recipeId);
        });
        jdbc.update("""
            INSERT INTO uplift.recipe
                (project_id, recipe_id, label, description, kind, status, notes,
                 decided_by, decided_at)
            VALUES (?, ?, ?, ?, 'custom', 'accepted', ?, ?, now())
            """, projectId, recipeId, label, description, notes, user);

        RecipeRow row = recipes.findOne(projectId, recipeId).orElseThrow();

        ProvenanceEmitter.Event ev = ProvenanceEmitter.Event.of(
                projectId, "human", user == null ? "anonymous" : user, "custom_recipe_created");
        ev.output = Map.of("recipeId", recipeId, "label", label);
        ev.links = List.of("recipe:" + row.id());
        prov.emit(ev);

        return row;
    }

    @Transactional
    public void delete(UUID projectId, UUID recipeId) {
        RecipeRow row = recipes.findById(recipeId)
                .orElseThrow(() -> new IllegalArgumentException("recipe not found"));
        if (!row.projectId().equals(projectId)) {
            throw new IllegalArgumentException("recipe belongs to a different project");
        }
        jdbc.update("DELETE FROM uplift.recipe WHERE id = ?", recipeId);
    }

    public Map<String, Object> status(UUID projectId) {
        List<RecipeRow> all = recipes.findByProject(projectId);

        // Pre-compute finding/module rollups once.
        Map<UUID, ModuleRow> modById = runs.latest(projectId)
                .map(r -> modules.findByRun(r.id()).stream()
                        .collect(Collectors.toMap(ModuleRow::id, m -> m)))
                .orElse(Map.of());

        List<Map<String, Object>> recipeJson = all.stream().map(r -> {
            List<String> findingIds = parseUuidStringList(r.findingIds());
            List<String> moduleIds  = parseUuidStringList(r.moduleIds());
            List<Map<String, Object>> moduleSummaries = moduleIds.stream()
                    .map(id -> modById.get(UUID.fromString(id)))
                    .filter(Objects::nonNull)
                    .map(m -> Map.<String, Object>of(
                            "id", m.id(),
                            "name", m.name(),
                            "packageName", m.packageName()))
                    .toList();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("recipeId", r.recipeId());
            m.put("label", r.label());
            m.put("description", r.description());
            m.put("kind", r.kind());
            m.put("status", r.status());
            m.put("notes", r.notes());
            m.put("findingCount", findingIds.size());
            m.put("modules", moduleSummaries);
            m.put("decidedBy", r.decidedBy());
            m.put("decidedAt", r.decidedAt());
            m.put("createdAt", r.createdAt());
            return m;
        }).toList();

        long accepted = all.stream().filter(r -> "accepted".equals(r.status())).count();
        long proposed = all.stream().filter(r -> "proposed".equals(r.status())).count();
        long rejected = all.stream().filter(r -> "rejected".equals(r.status())).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recipes", recipeJson);
        out.put("counts", Map.of(
                "total", all.size(),
                "accepted", accepted,
                "proposed", proposed,
                "rejected", rejected));
        out.put("readyForGate", accepted >= 1);
        return out;
    }

    public Map<String, Object> findingsForRecipe(UUID projectId, UUID recipeId) {
        RecipeRow row = recipes.findById(recipeId)
                .orElseThrow(() -> new IllegalArgumentException("recipe not found"));
        if (!row.projectId().equals(projectId)) {
            throw new IllegalArgumentException("recipe belongs to a different project");
        }
        List<String> ids = parseUuidStringList(row.findingIds());
        if (ids.isEmpty()) return Map.of("findings", List.of());

        // Pull rows in the recorded order.
        Map<UUID, FindingRow> byId = new HashMap<>();
        runs.latest(projectId).ifPresent(r ->
            findings.findByRun(r.id()).forEach(f -> byId.put(f.id(), f)));

        List<Map<String, Object>> rows = ids.stream()
                .map(UUID::fromString)
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", f.id());
                    m.put("ruleId", f.ruleId());
                    m.put("ruleLabel", f.ruleLabel());
                    m.put("severity", f.severity());
                    m.put("filePath", f.filePath());
                    m.put("lineStart", f.lineStart());
                    m.put("lineEnd", f.lineEnd());
                    m.put("snippet", f.snippet());
                    return m;
                })
                .toList();

        return Map.of("findings", rows);
    }

    /* ---------------- helpers ---------------- */

    private static String jsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values.get(i).replace("\"", "\\\"")).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static List<String> parseUuidStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        // Tiny parser: we know we only emit "..." entries separated by commas.
        List<String> out = new ArrayList<>();
        for (String tok : json.replaceAll("[\\[\\]\\s]", "").split(",")) {
            String s = tok.replace("\"", "");
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
