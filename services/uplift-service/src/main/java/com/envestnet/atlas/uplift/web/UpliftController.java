package com.envestnet.atlas.uplift.web;

import com.envestnet.atlas.uplift.auth.GatewayIdentity;
import com.envestnet.atlas.uplift.domain.CharCaseRow;
import com.envestnet.atlas.uplift.domain.CharRunRow;
import com.envestnet.atlas.uplift.domain.CutoverRow;
import com.envestnet.atlas.uplift.domain.MigrationChangeRow;
import com.envestnet.atlas.uplift.domain.MigrationRunRow;
import com.envestnet.atlas.uplift.domain.RecipeRow;
import com.envestnet.atlas.uplift.domain.ScanRunRow;
import com.envestnet.atlas.uplift.domain.StranglerStepRow;
import com.envestnet.atlas.uplift.service.CharService;
import com.envestnet.atlas.uplift.service.CutoverService;
import com.envestnet.atlas.uplift.service.MigrationService;
import com.envestnet.atlas.uplift.service.RecipeService;
import com.envestnet.atlas.uplift.service.StranglerService;
import com.envestnet.atlas.uplift.service.UpliftService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal/uplift")
public class UpliftController {

    private final UpliftService svc;
    private final RecipeService recipeSvc;
    private final StranglerService stranglerSvc;
    private final MigrationService migrationSvc;
    private final CharService charSvc;
    private final CutoverService cutoverSvc;

    public UpliftController(UpliftService svc,
                            RecipeService recipeSvc,
                            StranglerService stranglerSvc,
                            MigrationService migrationSvc,
                            CharService charSvc,
                            CutoverService cutoverSvc) {
        this.svc = svc;
        this.recipeSvc = recipeSvc;
        this.stranglerSvc = stranglerSvc;
        this.migrationSvc = migrationSvc;
        this.charSvc = charSvc;
        this.cutoverSvc = cutoverSvc;
    }

    @PostMapping("/projects/{pid}/inventory/run")
    public ResponseEntity<Map<String, Object>> run(@PathVariable UUID pid,
                                                   @RequestBody RunRequest req) throws Exception {
        ScanRunRow r = svc.run(pid, req.sourcePath());
        return ResponseEntity.ok(Map.of(
                "runId", r.id(),
                "status", r.status(),
                "startedAt", r.startedAt(),
                "finishedAt", r.finishedAt()
        ));
    }

    @GetMapping("/projects/{pid}/inventory/status")
    public Map<String, Object> status(@PathVariable UUID pid) {
        return svc.status(pid);
    }

    /* ---------------- Recipe Authoring (Stage B) ---------------- */

    @PostMapping("/projects/{pid}/recipes/seed")
    public Map<String, Object> seedRecipes(@PathVariable UUID pid) {
        List<RecipeRow> seeded = recipeSvc.seedFromFindings(pid);
        return Map.of("ok", true, "count", seeded.size());
    }

    @GetMapping("/projects/{pid}/recipes")
    public Map<String, Object> recipeStatus(@PathVariable UUID pid) {
        return recipeSvc.status(pid);
    }

    @PostMapping("/projects/{pid}/recipes/{rid}/decision")
    public ResponseEntity<?> decide(@PathVariable UUID pid,
                                    @PathVariable UUID rid,
                                    @RequestBody DecisionRequest req) {
        try {
            RecipeRow r = recipeSvc.decide(pid, rid, req.status(), req.notes(),
                    GatewayIdentity.resolveActor(req.user()));
            return ResponseEntity.ok(Map.of("ok", true, "id", r.id(), "status", r.status()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/projects/{pid}/recipes")
    public ResponseEntity<?> createCustom(@PathVariable UUID pid,
                                          @RequestBody CustomRecipeRequest req) {
        try {
            RecipeRow r = recipeSvc.createCustom(pid, req.recipeId(), req.label(),
                    req.description(), req.notes(),
                    GatewayIdentity.resolveActor(req.user()));
            return ResponseEntity.ok(Map.of("ok", true, "id", r.id()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/projects/{pid}/recipes/{rid}")
    public ResponseEntity<?> delete(@PathVariable UUID pid, @PathVariable UUID rid) {
        try {
            recipeSvc.delete(pid, rid);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/projects/{pid}/recipes/{rid}/findings")
    public ResponseEntity<?> recipeFindings(@PathVariable UUID pid, @PathVariable UUID rid) {
        try {
            return ResponseEntity.ok(recipeSvc.findingsForRecipe(pid, rid));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /* ---------------- Strangler Designer (Stage C) ---------------- */

    @PostMapping("/projects/{pid}/strangler/seed")
    public ResponseEntity<?> seedStrangler(@PathVariable UUID pid) {
        try {
            List<StranglerStepRow> rows = stranglerSvc.seed(pid);
            return ResponseEntity.ok(Map.of("ok", true, "count", rows.size()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/projects/{pid}/strangler")
    public Map<String, Object> stranglerStatus(@PathVariable UUID pid) {
        return stranglerSvc.status(pid);
    }

    @PutMapping("/projects/{pid}/strangler/{sid}")
    public ResponseEntity<?> updateStep(@PathVariable UUID pid,
                                        @PathVariable UUID sid,
                                        @RequestBody UpdateStepRequest req) {
        try {
            StranglerStepRow row = stranglerSvc.updateStep(pid, sid,
                    req.status(), req.facadeNotes(),
                    GatewayIdentity.resolveActor(req.user()));
            return ResponseEntity.ok(Map.of("ok", true, "id", row.id(), "status", row.status()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/projects/{pid}/strangler/{sid}/order")
    public ResponseEntity<?> reorderStep(@PathVariable UUID pid,
                                         @PathVariable UUID sid,
                                         @RequestBody ReorderRequest req) {
        try {
            stranglerSvc.reorder(pid, sid, req.delta());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/projects/{pid}/strangler/{sid}")
    public ResponseEntity<?> deleteStep(@PathVariable UUID pid, @PathVariable UUID sid) {
        try {
            stranglerSvc.delete(pid, sid);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /* ---------------- Module Migration (Stage D) ---------------- */

    @PostMapping("/projects/{pid}/migrations/{sid}")
    public ResponseEntity<?> runMigration(@PathVariable UUID pid, @PathVariable UUID sid) {
        try {
            MigrationRunRow row = migrationSvc.run(pid, sid);
            return ResponseEntity.ok(Map.of("ok", true, "runId", row.id(), "status", row.status()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/projects/{pid}/migrations/step/{sid}")
    public Map<String, Object> migrationForStep(@PathVariable UUID pid, @PathVariable UUID sid) {
        return migrationSvc.statusForStep(pid, sid);
    }

    @GetMapping("/projects/{pid}/migrations")
    public Map<String, Object> migrationsForProject(@PathVariable UUID pid) {
        return migrationSvc.projectStatus(pid);
    }

    @GetMapping("/migrations/{rid}/files")
    public ResponseEntity<?> migrationFileDiff(@PathVariable UUID rid,
                                                @RequestParam("path") String path) {
        return migrationSvc.changeFor(rid, path)
                .<ResponseEntity<?>>map(c -> ResponseEntity.ok(Map.of(
                        "filePath", c.filePath(),
                        "recipeId", c.recipeId(),
                        "changes", c.changes(),
                        "diff", c.diffText() == null ? "" : c.diffText())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /* ---------------- Characterization Validation (Stage E) ---------------- */

    @PostMapping("/projects/{pid}/characterize/run")
    public ResponseEntity<?> runCharacterization(@PathVariable UUID pid,
                                                  @RequestBody(required = false) CharRunRequest req) {
        try {
            int per = req == null || req.casesPerModule() == null ? 30 : req.casesPerModule();
            CharRunRow row = charSvc.run(pid, per);
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "runId", row.id(),
                    "passCount", row.passCount(),
                    "benignCount", row.benignCount(),
                    "regressionCount", row.regressionCount()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/projects/{pid}/characterize/status")
    public Map<String, Object> charStatus(@PathVariable UUID pid) {
        return charSvc.status(pid);
    }

    @PostMapping("/projects/{pid}/characterize/cases/{cid}/triage")
    public ResponseEntity<?> triageCase(@PathVariable UUID pid,
                                         @PathVariable UUID cid,
                                         @RequestBody TriageRequest req) {
        try {
            CharCaseRow row = charSvc.triage(cid, req.state(),
                    GatewayIdentity.resolveActor(req.user()));
            return ResponseEntity.ok(Map.of("ok", true, "id", row.id(), "state", row.triageState()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /* ---------------- Cutover & Decommission (Stage F) ---------------- */

    @PostMapping("/projects/{pid}/cutover/seed")
    public ResponseEntity<?> seedCutover(@PathVariable UUID pid) {
        try {
            List<CutoverRow> rows = cutoverSvc.seed(pid);
            return ResponseEntity.ok(Map.of("ok", true, "count", rows.size()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/projects/{pid}/cutover")
    public Map<String, Object> cutoverStatus(@PathVariable UUID pid) {
        return cutoverSvc.status(pid);
    }

    @PutMapping("/projects/{pid}/cutover/{cid}")
    public ResponseEntity<?> updateCutover(@PathVariable UUID pid,
                                            @PathVariable UUID cid,
                                            @RequestBody CutoverUpdateRequest req) {
        try {
            CutoverRow row = cutoverSvc.updateState(pid, cid, req.state(),
                    req.trafficPercent(), req.notes(),
                    GatewayIdentity.resolveActor(req.user()));
            return ResponseEntity.ok(Map.of("ok", true, "id", row.id(), "state", row.state(),
                    "trafficPercent", row.trafficPercent()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/projects/{pid}/cutover/{cid}/checklist/{itemId}")
    public ResponseEntity<?> toggleChecklist(@PathVariable UUID pid,
                                              @PathVariable UUID cid,
                                              @PathVariable String itemId,
                                              @RequestBody ChecklistToggleRequest req) {
        try {
            cutoverSvc.toggleChecklistItem(pid, cid, itemId, req.done(),
                    GatewayIdentity.resolveActor(req.user()));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping(value = "/projects/{pid}/cutover/closure", produces = "text/markdown")
    public ResponseEntity<String> closureDocument(@PathVariable UUID pid,
                                                   @RequestParam(value = "name",
                                                       defaultValue = "(unnamed project)") String name,
                                                   @RequestParam(value = "sourcePath",
                                                       required = false) String sourcePath) {
        String md = cutoverSvc.closureDocument(pid, name, sourcePath);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .body(md);
    }

    public record RunRequest(String sourcePath) {}
    public record DecisionRequest(String status, String notes, String user) {}
    public record CustomRecipeRequest(String recipeId, String label, String description,
                                       String notes, String user) {}
    public record UpdateStepRequest(String status, String facadeNotes, String user) {}
    public record ReorderRequest(int delta) {}
    public record CharRunRequest(Integer casesPerModule) {}
    public record TriageRequest(String state, String user) {}
    public record CutoverUpdateRequest(String state, Integer trafficPercent,
                                       String notes, String user) {}
    public record ChecklistToggleRequest(boolean done, String user) {}
}
