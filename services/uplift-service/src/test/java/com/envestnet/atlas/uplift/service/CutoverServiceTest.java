package com.envestnet.atlas.uplift.service;

import com.envestnet.atlas.uplift.domain.CutoverRow;
import com.envestnet.atlas.uplift.testsupport.BaseServiceTest;
import com.envestnet.atlas.uplift.testsupport.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class CutoverServiceTest extends BaseServiceTest {

    @Autowired CutoverService cutoverService;
    @Autowired Fixtures       fix;
    @Autowired JdbcTemplate   jdbc;

    @BeforeEach void cleanSlate() { fix.wipe(); }

    @Test
    void seedRequiresAtLeastOneReadyOrExtractedStrangerStep() {
        UUID pid = fix.aProject();
        var run = fix.aScanRun(pid, "/src");
        var mod = fix.aModule(pid, run.id(), "x", "com.x");
        // Insert a planned-only step. Cutover should refuse.
        jdbc.update("""
                INSERT INTO uplift.strangler_step
                    (project_id, module_id, sequence_no, status, recipe_ids)
                VALUES (?, ?, 1, 'planned', '[]'::jsonb)
                """, pid, mod.id());

        assertThatThrownBy(() -> cutoverService.seed(pid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ready");
    }

    @Test
    void seedCreatesOneCutoverPerEligibleStepWithKindAwareChecklist() {
        UUID pid = projectWithReadySteps(/*web*/ true, /*persist*/ true, /*jndi*/ true);

        List<CutoverRow> rows = cutoverService.seed(pid);

        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(r -> assertThat(r.state()).isEqualTo("planned"));

        Map<String, Object> status = cutoverService.status(pid);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jsonRows = (List<Map<String, Object>>) status.get("cutovers");

        // Each row should have a non-empty checklist.
        for (var row : jsonRows) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> checklist = (List<Map<String, Object>>) row.get("checklist");
            assertThat(checklist).isNotEmpty();
            // The 'web' module gets a "Reroute /api/* via Spring Cloud Gateway" item
            // — confirm by category.
            String moduleName = ((Map<?, ?>) row.get("module")).get("name").toString();
            if ("web".equals(moduleName)) {
                assertThat(checklist).anyMatch(item -> "infra".equals(item.get("category")));
            }
        }
    }

    @Test
    void updateStateRunsThePlannedShadowCanaryLiveProgression() {
        UUID pid = projectWithReadySteps(true, false, false);
        var rows = cutoverService.seed(pid);
        UUID cid = rows.get(0).id();

        var shadow = cutoverService.updateState(pid, cid, "shadow", null, null, "alice");
        assertThat(shadow.state()).isEqualTo("shadow");
        assertThat(shadow.trafficPercent()).isEqualTo(0);

        var canary = cutoverService.updateState(pid, cid, "canary", null, null, "alice");
        assertThat(canary.state()).isEqualTo("canary");
        assertThat(canary.trafficPercent()).isEqualTo(10);

        var live = cutoverService.updateState(pid, cid, "live", null, null, "alice");
        assertThat(live.state()).isEqualTo("live");
        assertThat(live.trafficPercent()).isEqualTo(100);
        assertThat(live.cutoverDate()).isNotNull();
        assertThat(live.approvedBy()).isEqualTo("alice");
    }

    @Test
    void updateStateRespectsExplicitTrafficOverride() {
        UUID pid = projectWithReadySteps(true, false, false);
        var rows = cutoverService.seed(pid);
        UUID cid = rows.get(0).id();

        var canary = cutoverService.updateState(pid, cid, "canary", 25, null, "alice");
        assertThat(canary.trafficPercent()).isEqualTo(25);

        // Out-of-range values clamp.
        var clamped = cutoverService.updateState(pid, cid, "canary", 9999, null, "alice");
        assertThat(clamped.trafficPercent()).isEqualTo(100);
    }

    @Test
    void liveTransitionMarksTheUnderlyingStrangerStepExtracted() {
        UUID pid = projectWithReadySteps(true, false, false);
        var rows = cutoverService.seed(pid);
        UUID cid = rows.get(0).id();

        cutoverService.updateState(pid, cid, "live", null, null, "alice");

        // The strangler_step.status for this row should now be 'extracted'.
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM uplift.strangler_step WHERE status = 'extracted'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void toggleChecklistItemFlipsDoneFlagAndRoundTrips() {
        UUID pid = projectWithReadySteps(true, false, false);
        var rows = cutoverService.seed(pid);
        UUID cid = rows.get(0).id();

        cutoverService.toggleChecklistItem(pid, cid, "smoke", true, "alice");
        cutoverService.toggleChecklistItem(pid, cid, "dep_tag", true, "alice");

        @SuppressWarnings("unchecked")
        Map<String, Object> ours =
                ((List<Map<String, Object>>) cutoverService.status(pid).get("cutovers")).get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checklist = (List<Map<String, Object>>) ours.get("checklist");

        assertThat(checklist)
                .filteredOn(item -> "smoke".equals(item.get("id")) || "dep_tag".equals(item.get("id")))
                .allMatch(item -> Boolean.TRUE.equals(item.get("done")));
    }

    @Test
    void readyForGateRequiresAllCutoversLiveOrDecommissionedAndChecklistComplete() {
        UUID pid = projectWithReadySteps(true, false, false);
        var rows = cutoverService.seed(pid);
        UUID cid = rows.get(0).id();

        Map<String, Object> initial = cutoverService.status(pid);
        assertThat(initial.get("readyForGate")).isEqualTo(false);

        cutoverService.updateState(pid, cid, "live", null, null, "alice");
        Map<String, Object> liveButChecklistOpen = cutoverService.status(pid);
        assertThat(liveButChecklistOpen.get("readyForGate")).isEqualTo(false);

        // Tick every checklist item.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>)
                ((List<Map<String, Object>>) liveButChecklistOpen.get("cutovers"))
                        .get(0).get("checklist");
        for (var item : items) {
            cutoverService.toggleChecklistItem(pid, cid, item.get("id").toString(), true, "alice");
        }

        Map<String, Object> ready = cutoverService.status(pid);
        assertThat(ready.get("readyForGate")).isEqualTo(true);
    }

    @Test
    void closureDocumentRollsUpEverySectionAsMarkdown() {
        UUID pid = projectWithReadySteps(true, false, false);
        cutoverService.seed(pid);

        String md = cutoverService.closureDocument(pid, "Test Project", "/src");

        assertThat(md)
                .contains("# Migration closure · Test Project")
                .contains("## Stage A · Inventory")
                .contains("## Stage F · Cutover & decommission");
    }

    /* ---------------- helpers ---------------- */

    /**
     * Build a project with N strangler steps in 'ready' state, each with one
     * accepted recipe attached. The boolean flags pick which module-kind
     * checklist defaults are exercised.
     */
    private UUID projectWithReadySteps(boolean web, boolean persistence, boolean jndi) {
        UUID pid = fix.aProject();
        var run  = fix.aScanRun(pid, "/src");
        int seq = 0;

        if (web) {
            var mod = fix.aModule(pid, run.id(), "web", "com.example.web");
            insertReadyStep(pid, mod.id(), ++seq);
        }
        if (persistence) {
            var mod = fix.aModule(pid, run.id(), "repo", "com.example.repo");
            insertReadyStep(pid, mod.id(), ++seq);
        }
        if (jndi) {
            var mod = fix.aModule(pid, run.id(), "naming", "com.example.naming");
            insertReadyStep(pid, mod.id(), ++seq);
        }
        return pid;
    }

    private void insertReadyStep(UUID pid, UUID moduleId, int seq) {
        jdbc.update("""
                INSERT INTO uplift.strangler_step
                    (project_id, module_id, sequence_no, status, recipe_ids)
                VALUES (?, ?, ?, 'ready', '[]'::jsonb)
                """, pid, moduleId, seq);
    }
}
