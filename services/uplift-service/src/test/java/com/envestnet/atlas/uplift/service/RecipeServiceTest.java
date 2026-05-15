package com.envestnet.atlas.uplift.service;

import com.envestnet.atlas.uplift.domain.RecipeRow;
import com.envestnet.atlas.uplift.testsupport.BaseServiceTest;
import com.envestnet.atlas.uplift.testsupport.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class RecipeServiceTest extends BaseServiceTest {

    @Autowired RecipeService recipeService;
    @Autowired Fixtures fix;

    @BeforeEach void cleanSlate() { fix.wipe(); }

    @Nested
    @DisplayName("seedFromFindings")
    class SeedFromFindings {

        @Test
        @DisplayName("creates one recipe per distinct suggestedRecipe")
        void deduplicatesByRecipeId() {
            UUID pid = fix.aProject();
            var run  = fix.aScanRun(pid, "/src");
            var mod  = fix.aModule(pid, run.id(), "domain", "com.envestnet.legacy.domain");
            fix.aFinding(pid, run.id(), mod.id(), "javax_persistence",
                    "org.openrewrite.java.migrate.jakarta.JavaxPersistenceToJakartaPersistence");
            fix.aFinding(pid, run.id(), mod.id(), "javax_persistence",
                    "org.openrewrite.java.migrate.jakarta.JavaxPersistenceToJakartaPersistence");
            fix.aFinding(pid, run.id(), mod.id(), "javax_servlet",
                    "org.openrewrite.java.migrate.jakarta.JavaxServletToJakartaServlet");

            List<RecipeRow> seeded = recipeService.seedFromFindings(pid);

            assertThat(seeded)
                    .extracting(RecipeRow::recipeId)
                    .containsExactlyInAnyOrder(
                            "org.openrewrite.java.migrate.jakarta.JavaxPersistenceToJakartaPersistence",
                            "org.openrewrite.java.migrate.jakarta.JavaxServletToJakartaServlet");
            assertThat(seeded).allSatisfy(r -> {
                assertThat(r.status()).isEqualTo("proposed");
                assertThat(r.kind()).isEqualTo("ootb");
            });
        }

        @Test
        @DisplayName("skips findings without a suggested recipe")
        void ignoresNullRecipes() {
            UUID pid = fix.aProject();
            var run  = fix.aScanRun(pid, "/src");
            var mod  = fix.aModule(pid, run.id(), "web", "com.envestnet.legacy.web");
            fix.aFinding(pid, run.id(), mod.id(), "rule_a", null);
            fix.aFinding(pid, run.id(), mod.id(), "rule_b", "");
            fix.aFinding(pid, run.id(), mod.id(), "rule_c",
                    "org.openrewrite.java.spring.boot3.SpringBoot2To3");

            List<RecipeRow> seeded = recipeService.seedFromFindings(pid);

            assertThat(seeded).hasSize(1);
            assertThat(seeded.get(0).recipeId())
                    .isEqualTo("org.openrewrite.java.spring.boot3.SpringBoot2To3");
        }

        @Test
        @DisplayName("normalises (custom) suffix so duplicates fold into one row")
        void stripsCustomTag() {
            UUID pid = fix.aProject();
            var run  = fix.aScanRun(pid, "/src");
            var mod  = fix.aModule(pid, run.id(), "naming", "com.envestnet.legacy.naming");
            fix.aFinding(pid, run.id(), mod.id(), "ibm_websphere",
                    "atlas.recipes.IbmWebSphereToStandard (custom)");
            fix.aFinding(pid, run.id(), mod.id(), "ibm_websphere",
                    "atlas.recipes.IbmWebSphereToStandard");

            List<RecipeRow> seeded = recipeService.seedFromFindings(pid);

            assertThat(seeded).hasSize(1);
            assertThat(seeded.get(0).recipeId())
                    .isEqualTo("atlas.recipes.IbmWebSphereToStandard");
            assertThat(seeded.get(0).kind()).isEqualTo("custom");
        }

        @Test
        @DisplayName("preserves existing accept/reject decisions on re-seed")
        void preservesUserDecisions() {
            UUID pid = fix.aProject();
            var run  = fix.aScanRun(pid, "/src");
            var mod  = fix.aModule(pid, run.id(), "web", "com.envestnet.legacy.web");
            fix.aFinding(pid, run.id(), mod.id(), "javax_servlet",
                    "org.openrewrite.java.migrate.jakarta.JavaxServletToJakartaServlet");

            var first  = recipeService.seedFromFindings(pid);
            var seeded = first.stream().filter(r -> r.recipeId().contains("Servlet")).findFirst().orElseThrow();

            recipeService.decide(pid, seeded.id(), "accepted", "looks good", "alice");

            // New finding triggers re-seed; the accepted decision must survive.
            fix.aFinding(pid, run.id(), mod.id(), "javax_servlet",
                    "org.openrewrite.java.migrate.jakarta.JavaxServletToJakartaServlet");
            var second = recipeService.seedFromFindings(pid);

            var still = second.stream().filter(r -> r.recipeId().contains("Servlet")).findFirst().orElseThrow();
            assertThat(still.status()).isEqualTo("accepted");
            assertThat(still.notes()).isEqualTo("looks good");
            assertThat(still.decidedBy()).isEqualTo("alice");
        }

        @Test
        @DisplayName("rejects when no inventory scan exists yet")
        void requiresScanRun() {
            UUID pid = fix.aProject();

            assertThatThrownBy(() -> recipeService.seedFromFindings(pid))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No inventory scan");
        }
    }

    @Nested
    @DisplayName("decide")
    class Decide {

        @Test
        @DisplayName("transitions proposed → accepted with audit metadata")
        void transitionsToAccepted() {
            UUID pid = seedSingleRecipe();
            var recipe = recipeService.status(pid).get("recipes");
            UUID rid = firstRecipeId(pid);

            recipeService.decide(pid, rid, "accepted", "ship it", "bob");

            var row = recipeService.status(pid);
            @SuppressWarnings("unchecked")
            Map<String, Object> first = ((List<Map<String, Object>>) row.get("recipes")).get(0);
            assertThat(first.get("status")).isEqualTo("accepted");
            assertThat(first.get("notes")).isEqualTo("ship it");
            assertThat(first.get("decidedBy")).isEqualTo("bob");
            assertThat(first.get("decidedAt")).isNotNull();
        }

        @Test
        @DisplayName("rejects an unknown status string")
        void rejectsBogusStatus() {
            UUID pid = seedSingleRecipe();
            UUID rid = firstRecipeId(pid);

            assertThatThrownBy(() -> recipeService.decide(pid, rid, "exploded", null, "x"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("invalid status");
        }

        @Test
        @DisplayName("rejects a recipe that belongs to a different project")
        void rejectsCrossProject() {
            UUID pid = seedSingleRecipe();
            UUID other = fix.aProject();
            UUID rid = firstRecipeId(pid);

            assertThatThrownBy(() -> recipeService.decide(other, rid, "accepted", null, "x"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("createCustom")
    class CreateCustom {

        @Test
        @DisplayName("creates an auto-accepted custom recipe")
        void createsAutoAccepted() {
            UUID pid = fix.aProject();
            // Need at least a scan_run for the project so other paths don't break.
            fix.aScanRun(pid, "/src");

            var created = recipeService.createCustom(
                    pid, "atlas.recipes.RemoveLog4j1", "Replace Log4j1", "desc", "notes", "carol");

            assertThat(created.kind()).isEqualTo("custom");
            assertThat(created.status()).isEqualTo("accepted");
            assertThat(created.label()).isEqualTo("Replace Log4j1");
        }

        @Test
        @DisplayName("rejects empty inputs")
        void rejectsBlanks() {
            UUID pid = fix.aProject();
            assertThatThrownBy(() -> recipeService.createCustom(pid, "", "label", null, null, "x"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> recipeService.createCustom(pid, "atlas.x", "", null, null, "x"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects duplicates within the same project")
        void rejectsDuplicates() {
            UUID pid = fix.aProject();
            recipeService.createCustom(pid, "atlas.recipes.X", "X", null, null, "u");

            assertThatThrownBy(() ->
                    recipeService.createCustom(pid, "atlas.recipes.X", "X again", null, null, "u"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already exists");
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("removes the row")
        void removes() {
            UUID pid = fix.aProject();
            var c = recipeService.createCustom(pid, "atlas.X", "X", null, null, "u");

            recipeService.delete(pid, c.id());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> recipes =
                    (List<Map<String, Object>>) recipeService.status(pid).get("recipes");
            assertThat(recipes).isEmpty();
        }

        @Test
        @DisplayName("refuses cross-project delete")
        void crossProject() {
            UUID a = fix.aProject();
            UUID b = fix.aProject();
            var c = recipeService.createCustom(a, "atlas.X", "X", null, null, "u");

            assertThatThrownBy(() -> recipeService.delete(b, c.id()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("status")
    class Status {

        @Test
        @DisplayName("counts buckets and flips readyForGate when ≥1 accepted")
        void readyForGate() {
            UUID pid = seedSingleRecipe();

            @SuppressWarnings("unchecked")
            Map<String, Object> before = recipeService.status(pid);
            assertThat(before.get("readyForGate")).isEqualTo(false);

            UUID rid = firstRecipeId(pid);
            recipeService.decide(pid, rid, "accepted", null, "u");

            @SuppressWarnings("unchecked")
            Map<String, Object> after = recipeService.status(pid);
            assertThat(after.get("readyForGate")).isEqualTo(true);

            @SuppressWarnings("unchecked")
            Map<String, Object> counts = (Map<String, Object>) after.get("counts");
            assertThat(counts.get("accepted")).isEqualTo(1L);
            assertThat(counts.get("proposed")).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("findingsForRecipe")
    class FindingsForRecipe {

        @Test
        @DisplayName("returns the findings linked at seed time")
        void returnsLinkedFindings() {
            UUID pid = fix.aProject();
            var run  = fix.aScanRun(pid, "/src");
            var mod  = fix.aModule(pid, run.id(), "web", "com.web");
            fix.aFinding(pid, run.id(), mod.id(), "javax_servlet",
                    "org.openrewrite.java.migrate.jakarta.JavaxServletToJakartaServlet");
            fix.aFinding(pid, run.id(), mod.id(), "javax_servlet",
                    "org.openrewrite.java.migrate.jakarta.JavaxServletToJakartaServlet");

            recipeService.seedFromFindings(pid);
            UUID rid = firstRecipeId(pid);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> result =
                    (List<Map<String, Object>>) recipeService.findingsForRecipe(pid, rid).get("findings");

            assertThat(result).hasSize(2);
            assertThat(result.get(0)).containsEntry("ruleId", "javax_servlet");
        }
    }

    /* ---------------- helpers ---------------- */

    private UUID seedSingleRecipe() {
        UUID pid = fix.aProject();
        var run  = fix.aScanRun(pid, "/src");
        var mod  = fix.aModule(pid, run.id(), "web", "com.web");
        fix.aFinding(pid, run.id(), mod.id(), "javax_servlet",
                "org.openrewrite.java.migrate.jakarta.JavaxServletToJakartaServlet");
        recipeService.seedFromFindings(pid);
        return pid;
    }

    @SuppressWarnings("unchecked")
    private UUID firstRecipeId(UUID pid) {
        Map<String, Object> status = recipeService.status(pid);
        Map<String, Object> first =
                ((List<Map<String, Object>>) status.get("recipes")).get(0);
        return UUID.fromString(first.get("id").toString());
    }
}
