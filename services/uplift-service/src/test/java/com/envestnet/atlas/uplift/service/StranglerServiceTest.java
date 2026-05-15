package com.envestnet.atlas.uplift.service;

import com.envestnet.atlas.uplift.domain.StranglerStepRow;
import com.envestnet.atlas.uplift.testsupport.BaseServiceTest;
import com.envestnet.atlas.uplift.testsupport.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class StranglerServiceTest extends BaseServiceTest {

    @Autowired StranglerService stranglerService;
    @Autowired RecipeService    recipeService;
    @Autowired Fixtures         fix;

    @BeforeEach void cleanSlate() { fix.wipe(); }

    @Test
    void seedRequiresAtLeastOneAcceptedRecipe() {
        UUID pid = aProjectWithProposedRecipe();

        assertThatThrownBy(() -> stranglerService.seed(pid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Accept at least one recipe");
    }

    @Test
    void seedCreatesOneStepPerTargetedModule() {
        UUID pid = aProjectWithAcceptedRecipeAcross(2);

        var rows = stranglerService.seed(pid);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(StranglerStepRow::sequenceNo)
                .containsExactly(1, 2);
        assertThat(rows).allSatisfy(r -> assertThat(r.status()).isEqualTo("planned"));
    }

    @Test
    void seedOrdersHardestFirst() {
        UUID pid = fix.aProject();
        var run = fix.aScanRun(pid, "/src");
        var easy = fix.aModule(pid, run.id(), "easy", "com.easy", 50,  3, 1);
        var hard = fix.aModule(pid, run.id(), "hard", "com.hard", 50,  9, 1);
        // Two findings sharing the same recipe, each in a different module.
        fix.aFinding(pid, run.id(), easy.id(), "javax_servlet",
                "org.openrewrite.java.migrate.jakarta.JavaxServletToJakartaServlet");
        fix.aFinding(pid, run.id(), hard.id(), "javax_servlet",
                "org.openrewrite.java.migrate.jakarta.JavaxServletToJakartaServlet");
        recipeService.seedFromFindings(pid);
        acceptAll(pid);

        var rows = stranglerService.seed(pid);

        // First-in-sequence should be the harder module.
        assertThat(rows.get(0).moduleId()).isEqualTo(hard.id());
        assertThat(rows.get(1).moduleId()).isEqualTo(easy.id());
    }

    @Test
    void reorderSwapsAdjacentStepsAndKeepsSequenceContiguous() {
        UUID pid = aProjectWithAcceptedRecipeAcross(3);
        var rows = stranglerService.seed(pid);

        UUID firstId = rows.get(0).id();

        stranglerService.reorder(pid, firstId, +1);

        var after = ((List<Map<String, Object>>)
                stranglerService.status(pid).get("steps"));
        // The previously-first step should now be at sequence 2.
        Map<String, Object> moved = after.stream()
                .filter(m -> m.get("id").toString().equals(firstId.toString()))
                .findFirst().orElseThrow();
        assertThat(moved.get("sequenceNo")).isEqualTo(2);
        // Sequence numbers are still 1..N, no gaps.
        assertThat(after).extracting(m -> m.get("sequenceNo"))
                .containsExactly(1, 2, 3);
    }

    @Test
    void updateStepFlipsStatusAndRecordsAuditFields() {
        UUID pid = aProjectWithAcceptedRecipeAcross(1);
        var step = stranglerService.seed(pid).get(0);

        var after = stranglerService.updateStep(
                pid, step.id(), "ready", "facade route /api/orders → new module", "alice");

        assertThat(after.status()).isEqualTo("ready");
        assertThat(after.facadeNotes()).contains("/api/orders");
        assertThat(after.decidedBy()).isEqualTo("alice");
        assertThat(after.decidedAt()).isNotNull();
    }

    @Test
    void updateStepRejectsBogusStatus() {
        UUID pid = aProjectWithAcceptedRecipeAcross(1);
        var step = stranglerService.seed(pid).get(0);

        assertThatThrownBy(() ->
                stranglerService.updateStep(pid, step.id(), "exploded", null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteCompactsSequenceNumbers() {
        UUID pid = aProjectWithAcceptedRecipeAcross(3);
        var rows = stranglerService.seed(pid);

        stranglerService.delete(pid, rows.get(1).id());   // remove the middle one

        var after = (List<Map<String, Object>>)
                stranglerService.status(pid).get("steps");
        assertThat(after).hasSize(2);
        assertThat(after).extracting(m -> m.get("sequenceNo"))
                .containsExactly(1, 2);
    }

    @Test
    void statusFlipsReadyForGateOnceAtLeastOneStepIsReady() {
        UUID pid = aProjectWithAcceptedRecipeAcross(2);
        var rows = stranglerService.seed(pid);

        Map<String, Object> before = stranglerService.status(pid);
        assertThat(before.get("readyForGate")).isEqualTo(false);

        stranglerService.updateStep(pid, rows.get(0).id(), "ready", null, "u");

        Map<String, Object> after = stranglerService.status(pid);
        assertThat(after.get("readyForGate")).isEqualTo(true);
    }

    /* ---------------- helpers ---------------- */

    private UUID aProjectWithProposedRecipe() {
        UUID pid = fix.aProject();
        var run = fix.aScanRun(pid, "/src");
        var mod = fix.aModule(pid, run.id(), "web", "com.web");
        fix.aFinding(pid, run.id(), mod.id(), "javax_servlet",
                "org.openrewrite.java.migrate.jakarta.JavaxServletToJakartaServlet");
        recipeService.seedFromFindings(pid);
        return pid;
    }

    private UUID aProjectWithAcceptedRecipeAcross(int nModules) {
        UUID pid = fix.aProject();
        var run = fix.aScanRun(pid, "/src");
        for (int i = 0; i < nModules; i++) {
            var mod = fix.aModule(pid, run.id(), "mod" + i, "com.mod" + i,
                    100 + i * 50, /*difficulty*/ 5 + i, /*findings*/ 1);
            fix.aFinding(pid, run.id(), mod.id(), "javax_servlet" + i,
                    "atlas.recipes.MultiModule" + i);
        }
        recipeService.seedFromFindings(pid);
        acceptAll(pid);
        return pid;
    }

    @SuppressWarnings("unchecked")
    private void acceptAll(UUID pid) {
        Map<String, Object> status = recipeService.status(pid);
        for (Map<String, Object> r : (List<Map<String, Object>>) status.get("recipes")) {
            UUID rid = UUID.fromString(r.get("id").toString());
            recipeService.decide(pid, rid, "accepted", null, "u");
        }
    }
}
