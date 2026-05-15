package com.envestnet.atlas.uplift.service;

import com.envestnet.atlas.uplift.domain.MigrationRunRow;
import com.envestnet.atlas.uplift.repo.ScanRunRepository;
import com.envestnet.atlas.uplift.testsupport.BaseServiceTest;
import com.envestnet.atlas.uplift.testsupport.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class MigrationServiceTest extends BaseServiceTest {

    @Autowired MigrationService    migrationService;
    @Autowired StranglerService    stranglerService;
    @Autowired RecipeService       recipeService;
    @Autowired ScanRunRepository   runs;
    @Autowired JdbcTemplate        jdbc;
    @Autowired Fixtures            fix;

    @BeforeEach void cleanSlate() { fix.wipe(); }

    @Test
    void runRewritesJavaxImportsAcrossModuleSources(@TempDir Path src) throws IOException {
        UUID pid = setupProjectWithStep(src, /*moduleName*/ "web",
                "com.example.web",
                """
                package com.example.web;

                import javax.servlet.http.HttpServletRequest;
                import javax.persistence.Entity;

                public class Foo {}
                """,
                "atlas.recipes.IbmWebSphereToStandard");   // unrelated recipe = 0 changes
        UUID stepId = firstStepId(pid);

        // Switch the step's recipe to one that DOES rewrite javax.servlet:
        switchRecipeFor(pid, stepId,
                "org.openrewrite.java.migrate.jakarta.JavaxServletToJakartaServlet");

        MigrationRunRow run = migrationService.run(pid, stepId);

        assertThat(run.status()).isEqualTo("completed");

        Map<String, Object> state = migrationService.statusForStep(pid, stepId);
        @SuppressWarnings("unchecked")
        Map<String, Object> r = (Map<String, Object>) state.get("run");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) state.get("changes");

        assertThat(r.get("status")).isEqualTo("completed");
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).get("filePath").toString())
                .endsWith("Foo.java");
        assertThat(((Number) changes.get(0).get("changes")).intValue())
                .isPositive();
    }

    @Test
    void runProducesUnifiedDiffWithExpectedFromAndTo(@TempDir Path src) throws IOException {
        UUID pid = setupProjectWithStep(src, "domain", "com.example.domain",
                "package com.example.domain;\nimport javax.persistence.Id;\n",
                "org.openrewrite.java.migrate.jakarta.JavaxPersistenceToJakartaPersistence");
        UUID stepId = firstStepId(pid);

        MigrationRunRow run = migrationService.run(pid, stepId);
        var change = migrationService.changeFor(run.id(),
                "src/main/java/com/example/domain/Foo.java").orElseThrow();

        assertThat(change.diffText())
                .contains("--- a/src/main/java/com/example/domain/Foo.java")
                .contains("+++ b/src/main/java/com/example/domain/Foo.java")
                .contains("-import javax.persistence.Id;")
                .contains("+import jakarta.persistence.Id;");
    }

    @Test
    void runAdvancesPlannedStrangerStepToReady(@TempDir Path src) throws IOException {
        UUID pid = setupProjectWithStep(src, "service", "com.example.service",
                "package com.example.service;\nimport javax.servlet.Filter;\n",
                "org.openrewrite.java.migrate.jakarta.JavaxServletToJakartaServlet");
        UUID stepId = firstStepId(pid);

        // Before: planned
        Map<String, Object> beforeStep = stepStatus(stranglerService.status(pid), stepId);
        assertThat(beforeStep.get("status")).isEqualTo("planned");

        migrationService.run(pid, stepId);

        Map<String, Object> afterStep = stepStatus(stranglerService.status(pid), stepId);
        assertThat(afterStep.get("status")).isEqualTo("ready");
    }

    @Test
    void runFailsWhenStepHasNoRecipesAttached() {
        UUID pid = fix.aProject();
        var run = fix.aScanRun(pid, "/nonexistent");
        var mod = fix.aModule(pid, run.id(), "x", "com.x");
        // Insert a strangler step with empty recipe_ids:
        UUID stepId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO uplift.strangler_step
                    (id, project_id, module_id, sequence_no, status, recipe_ids)
                VALUES (?, ?, ?, 1, 'planned', '[]'::jsonb)
                """, stepId, pid, mod.id());

        assertThatThrownBy(() -> migrationService.run(pid, stepId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no recipes");
    }

    @Test
    void runFailsCleanlyWhenSourcePathIsMissing() {
        // Set up a project + step where the scan_run points at a path that doesn't
        // exist on disk. The service should record the run as failed but not throw
        // out of the Spring transaction in a way that corrupts later state.
        UUID pid = fix.aProject();
        var run = fix.aScanRun(pid, "/path/that/does/not/exist");
        var mod = fix.aModule(pid, run.id(), "x", "com.x");
        var custom = recipeService.createCustom(pid, "atlas.X", "X", null, null, "u");
        UUID stepId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO uplift.strangler_step
                    (id, project_id, module_id, sequence_no, status, recipe_ids)
                VALUES (?, ?, ?, 1, 'planned', ('["' || ? || '"]')::jsonb)
                """, stepId, pid, mod.id(), custom.id().toString());

        assertThatThrownBy(() -> migrationService.run(pid, stepId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("migration failed");
    }

    @Test
    void projectStatusReadyForGateRequiresAtLeastOneCompletedRun(@TempDir Path src) throws IOException {
        UUID pid = setupProjectWithStep(src, "web", "com.example.web",
                "package com.example.web;\nimport javax.servlet.Filter;\n",
                "org.openrewrite.java.migrate.jakarta.JavaxServletToJakartaServlet");
        UUID stepId = firstStepId(pid);

        Map<String, Object> empty = migrationService.projectStatus(pid);
        assertThat(empty.get("readyForGate")).isEqualTo(false);

        migrationService.run(pid, stepId);

        Map<String, Object> ready = migrationService.projectStatus(pid);
        assertThat(ready.get("readyForGate")).isEqualTo(true);
    }

    /* ---------------- helpers ---------------- */

    /**
     * Drops a one-file Java module under {@code src} and seeds the full chain
     * (project → scan_run → module → finding → recipe → strangler step) so
     * a single migration can be run.
     */
    private UUID setupProjectWithStep(Path src, String moduleName, String pkg,
                                       String javaContent, String recipeFqn) throws IOException {
        // Layout: <src>/src/main/java/<pkg-as-path>/Foo.java
        Path file = src.resolve("src/main/java/" + pkg.replace('.', '/') + "/Foo.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, javaContent, StandardCharsets.UTF_8);

        UUID pid = fix.aProject();
        var scan = fix.aScanRun(pid, src.toString());
        var mod  = fix.aModule(pid, scan.id(), moduleName, pkg);
        fix.aFinding(pid, scan.id(), mod.id(), "rule", recipeFqn);
        recipeService.seedFromFindings(pid);
        // Accept all proposed:
        @SuppressWarnings("unchecked")
        var status = recipeService.status(pid);
        for (var r : (List<Map<String, Object>>) status.get("recipes")) {
            recipeService.decide(pid, UUID.fromString(r.get("id").toString()),
                    "accepted", null, "u");
        }
        stranglerService.seed(pid);
        return pid;
    }

    private UUID firstStepId(UUID pid) {
        @SuppressWarnings("unchecked")
        var steps = (List<Map<String, Object>>)
                stranglerService.status(pid).get("steps");
        return UUID.fromString(steps.get(0).get("id").toString());
    }

    /** Look up the {step} entry inside StranglerService.status() output. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> stepStatus(Map<String, Object> stranglerStatus, UUID stepId) {
        return ((List<Map<String, Object>>) stranglerStatus.get("steps")).stream()
                .filter(m -> m.get("id").toString().equals(stepId.toString()))
                .findFirst().orElseThrow();
    }

    /**
     * Replace the recipe attached to an existing strangler step. Used when the
     * test needs to swap from a "no-op" recipe to one that actually rewrites.
     */
    private void switchRecipeFor(UUID pid, UUID stepId, String recipeFqn) {
        // Fetch the recipe row (created by the surrounding setup) and rewrite
        // the step's recipe_ids JSONB array in-place.
        @SuppressWarnings("unchecked")
        var recipes = (List<Map<String, Object>>)
                recipeService.status(pid).get("recipes");
        UUID recipeId = recipes.stream()
                .filter(r -> recipeFqn.equals(r.get("recipeId")))
                .map(r -> UUID.fromString(r.get("id").toString()))
                .findFirst()
                .orElseGet(() -> {
                    // If the recipe isn't seeded yet, create it as custom and accept it.
                    var c = recipeService.createCustom(pid, recipeFqn, recipeFqn, null, null, "u");
                    return c.id();
                });
        jdbc.update("""
                UPDATE uplift.strangler_step
                   SET recipe_ids = ('["' || ? || '"]')::jsonb
                 WHERE id = ?
                """, recipeId.toString(), stepId);
    }
}
