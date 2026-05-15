package com.envestnet.atlas.prov.service;

import com.envestnet.atlas.prov.testsupport.BaseServiceTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ProvServiceTest extends BaseServiceTest {

    @Autowired ProvService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void cleanSlate() {
        jdbc.execute("TRUNCATE prov.entry RESTART IDENTITY CASCADE");
    }

    /* ---------------- emit ---------------- */

    @Test
    void emitWritesAStructuredEventWithAllFields() {
        UUID pid = UUID.randomUUID();
        UUID id = service.emit(new ProvService.EmitRequest(
                pid, "agent", "code-archaeology", "agent_narrative",
                Map.of("system", "you are an SOAP archaeologist"),
                "claude-opus-4-7",
                Map.of("text", "the operation submits an allocation"),
                List.of(Map.of("name", "read_file", "args", Map.of("path", "/x"))),
                100, 200, 0.0042, 1450,
                List.of("operation:abc-123", "run:def-456"),
                null
        ));

        assertThat(id).isNotNull();

        var rows = service.query(pid, new ProvService.Filter(), 100);
        assertThat(rows).hasSize(1);
        var row = rows.get(0);

        assertThat(row.get("actorKind")).isEqualTo("agent");
        assertThat(row.get("actorId")).isEqualTo("code-archaeology");
        assertThat(row.get("action")).isEqualTo("agent_narrative");
        assertThat(row.get("model")).isEqualTo("claude-opus-4-7");
        assertThat(row.get("tokensIn")).isEqualTo(100);
        assertThat(row.get("tokensOut")).isEqualTo(200);
        assertThat(row.get("latencyMs")).isEqualTo(1450);
        assertThat(row.get("links")).asList()
                .containsExactlyInAnyOrder("operation:abc-123", "run:def-456");
    }

    @Test
    void emitDefaultsActorKindToSystemAndActorIdToUnknown() {
        UUID pid = UUID.randomUUID();
        service.emit(new ProvService.EmitRequest(
                pid, null, null, "background_task",
                null, null, null, null, null, null, null, null, null, null));

        var rows = service.query(pid, new ProvService.Filter(), 10);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("actorKind")).isEqualTo("system");
        assertThat(rows.get(0).get("actorId")).isEqualTo("unknown");
    }

    @Test
    void emitToleratesUnserialisableShapesByCoercingToNull() {
        // Fall back to logged-and-swallowed behaviour: emit doesn't throw,
        // returns a UUID, the row may or may not exist if the JSONB coercion
        // failed. We just want to confirm no exception leaks.
        UUID pid = UUID.randomUUID();
        UUID id = service.emit(new ProvService.EmitRequest(
                pid, "system", "x", "test_action",
                Map.of("ok", "value"), null, Map.of(), null, 0, 0, 0.0, 0,
                List.of(), null));
        assertThat(id).isNotNull();
    }

    /* ---------------- query ---------------- */

    @Test
    void queryFiltersByAction() {
        UUID pid = UUID.randomUUID();
        emit(pid, "agent", "x", "agent_narrative");
        emit(pid, "human", "alice", "decision_resolved");
        emit(pid, "system", "atlas", "bundle_built");

        var f = new ProvService.Filter();
        f.action = "agent_narrative";
        var rows = service.query(pid, f, 100);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("action")).isEqualTo("agent_narrative");
    }

    @Test
    void queryFiltersByActorKindAndActorId() {
        UUID pid = UUID.randomUUID();
        emit(pid, "agent", "code-archaeology", "agent_narrative");
        emit(pid, "agent", "diff-triage",      "agent_narrative");
        emit(pid, "human", "alice",            "decision_resolved");

        var byKind = new ProvService.Filter();
        byKind.actorKind = "agent";
        assertThat(service.query(pid, byKind, 100)).hasSize(2);

        var byActor = new ProvService.Filter();
        byActor.actorId = "alice";
        assertThat(service.query(pid, byActor, 100)).hasSize(1);
    }

    @Test
    void queryFreeTextSearchesAcrossActionActorOutput() {
        UUID pid = UUID.randomUUID();
        // Output contains "submitAllocation"; action and actor don't.
        service.emit(new ProvService.EmitRequest(
                pid, "agent", "x", "agent_narrative",
                null, null, Map.of("operation", "submitAllocation"), null,
                null, null, null, null, null, null));
        emit(pid, "agent", "x", "agent_other");

        var f = new ProvService.Filter();
        f.q = "submitalloc";              // case-insensitive
        var rows = service.query(pid, f, 100);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("action")).isEqualTo("agent_narrative");
    }

    @Test
    void queryClampsLimitToOneAtMinimumAndOneThousandAtMax() {
        UUID pid = UUID.randomUUID();
        for (int i = 0; i < 3; i++) emit(pid, "system", "atlas", "ping_" + i);

        // Negative / zero limits must be coerced to >=1 (no SQL error).
        var negative = service.query(pid, new ProvService.Filter(), -5);
        assertThat(negative).hasSize(1);

        // Huge limits must not exceed the stored row count (no SQL error).
        var huge = service.query(pid, new ProvService.Filter(), 99_999);
        assertThat(huge).hasSize(3);
    }

    @Test
    void queryScopesToTheRequestedProject() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        emit(a, "system", "x", "in_a");
        emit(b, "system", "x", "in_b");

        var rowsA = service.query(a, new ProvService.Filter(), 100);
        assertThat(rowsA).hasSize(1);
        assertThat(rowsA.get(0).get("action")).isEqualTo("in_a");
    }

    /* ---------------- aggregate ---------------- */

    @Test
    void aggregateRollsUpTotalsByActorAndAction() {
        UUID pid = UUID.randomUUID();
        emit(pid, "agent", "code-archaeology", "agent_narrative");
        emit(pid, "agent", "diff-triage",      "agent_narrative");
        emit(pid, "human", "alice",            "decision_resolved");

        var agg = service.aggregate(pid);

        assertThat(agg.get("total")).isEqualTo(3L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byActor = (List<Map<String, Object>>) agg.get("byActor");
        assertThat(byActor)
                .extracting(m -> m.get("kind"))
                .containsExactlyInAnyOrder("agent", "human");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byAction = (List<Map<String, Object>>) agg.get("byAction");
        assertThat(byAction).extracting(m -> m.get("action"))
                .contains("agent_narrative", "decision_resolved");
    }

    @Test
    void aggregateSumsTokenCountsAndCost() {
        UUID pid = UUID.randomUUID();
        service.emit(new ProvService.EmitRequest(
                pid, "agent", "x", "narrate",
                null, null, null, null, 100, 200, 0.01, 100, null, null));
        service.emit(new ProvService.EmitRequest(
                pid, "agent", "x", "narrate",
                null, null, null, null, 50, 75, 0.005, 100, null, null));

        var agg = service.aggregate(pid);
        @SuppressWarnings("unchecked")
        Map<String, Object> tokens = (Map<String, Object>) agg.get("tokens");

        assertThat(((Number) tokens.get("in")).longValue()).isEqualTo(150L);
        assertThat(((Number) tokens.get("out")).longValue()).isEqualTo(275L);
        assertThat(((Number) tokens.get("cost")).doubleValue()).isEqualTo(0.015);
    }

    /* ---------------- exportCsv ---------------- */

    @Test
    void exportCsvProducesHeaderAndOneRowPerEvent() {
        UUID pid = UUID.randomUUID();
        emit(pid, "agent", "code-archaeology", "agent_narrative");
        emit(pid, "human", "alice",            "decision_resolved");

        String csv = new String(service.exportCsv(pid));

        // Header + 2 data rows + trailing newline.
        long lines = csv.lines().count();
        assertThat(lines).isEqualTo(3);
        assertThat(csv).startsWith("ts,actor_kind,actor_id,action,");
        assertThat(csv).contains("agent_narrative");
        assertThat(csv).contains("decision_resolved");
    }

    @Test
    void exportCsvEscapesQuotesCommasAndNewlinesInActorId() {
        UUID pid = UUID.randomUUID();
        // CSV-hostile actor_id: contains commas, quotes and a newline.
        emit(pid, "human", "alice, the \"reviewer\"\nperson", "decision_resolved");

        String csv = new String(service.exportCsv(pid));

        // Must double-quote-wrap the field and double the embedded quotes.
        assertThat(csv).contains("\"alice, the \"\"reviewer\"\"\nperson\"");
    }

    /* ---------------- Phase 2L: workspace + user attribution + cost rollup ---------------- */

    @Test
    void emitPersistsWorkspaceIdAndUserEmailWhenSupplied() {
        UUID pid = UUID.randomUUID();
        UUID wsId = UUID.randomUUID();

        service.emit(new ProvService.EmitRequest(
                pid, "agent", "code-archaeology", "agent_narrative",
                null, "claude-sonnet-4-6", null, null,
                500, 1500, 0.027, 1200, null, null, null,
                wsId, "alice@envestnet.local"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT workspace_id, user_email FROM prov.entry WHERE project_id = ?", pid);
        assertThat(row.get("workspace_id")).isEqualTo(wsId);
        assertThat(row.get("user_email")).isEqualTo("alice@envestnet.local");
    }

    @Test
    void emitFallsBackToMdcForWorkspaceAndUserWhenBodyOmits() {
        UUID pid = UUID.randomUUID();
        UUID wsId = UUID.randomUUID();
        try {
            org.slf4j.MDC.put("workspaceId", wsId.toString());
            org.slf4j.MDC.put("userEmail",   "mdc@envestnet.local");
            // Pre-2L constructor — no workspace/user in body.
            service.emit(new ProvService.EmitRequest(
                    pid, "agent", "code-archaeology", "agent_narrative",
                    null, null, null, null, 100, 200, 0.01, 100, null, null));
        } finally {
            org.slf4j.MDC.clear();
        }

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT workspace_id, user_email FROM prov.entry WHERE project_id = ?", pid);
        assertThat(row.get("workspace_id")).isEqualTo(wsId);
        assertThat(row.get("user_email")).isEqualTo("mdc@envestnet.local");
    }

    @Test
    void workspaceCostRollsUpTotalsAcrossEveryRowInWindow() {
        UUID wsId = UUID.randomUUID();
        UUID pidA = UUID.randomUUID();
        UUID pidB = UUID.randomUUID();
        emitWithCost(pidA, wsId, "alice@envestnet.local", "claude-sonnet-4-6", 1000, 500, 0.0105);
        emitWithCost(pidA, wsId, "alice@envestnet.local", "claude-sonnet-4-6",  500, 250, 0.005);
        emitWithCost(pidB, wsId, "bob@envestnet.local",   "claude-opus-4-7",   2000, 1000, 0.105);

        var roll = service.workspaceCost(wsId,
                java.time.OffsetDateTime.now().minusDays(1),
                java.time.OffsetDateTime.now().plusDays(1));

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) roll.get("totals");
        assertThat(((Number) totals.get("calls")).longValue()).isEqualTo(3L);
        assertThat(((Number) totals.get("tokensIn")).longValue()).isEqualTo(3500L);
        assertThat(((Number) totals.get("tokensOut")).longValue()).isEqualTo(1750L);
        // Sum = 0.0105 + 0.005 + 0.105 = 0.1205. cost_usd is NUMERIC(10,4)
        // so each individual write rounds, but the sum should still be
        // tight to the input.
        assertThat(((java.math.BigDecimal) totals.get("costUsd")).doubleValue())
                .isCloseTo(0.1205, org.assertj.core.data.Offset.offset(0.0002));
    }

    @Test
    void workspaceCostByUserSortsBySpendDescending() {
        UUID wsId = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        emitWithCost(pid, wsId, "low@x",   "claude-sonnet-4-6", 100,  50,  0.001);
        emitWithCost(pid, wsId, "high@x",  "claude-opus-4-7",   200, 100,  0.018);
        emitWithCost(pid, wsId, "mid@x",   "claude-sonnet-4-6", 100,  50,  0.005);

        var roll = service.workspaceCost(wsId,
                java.time.OffsetDateTime.now().minusDays(1),
                java.time.OffsetDateTime.now().plusDays(1));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byUser = (List<Map<String, Object>>) roll.get("byUser");
        assertThat(byUser).extracting(m -> m.get("userEmail"))
                .containsExactly("high@x", "mid@x", "low@x");
    }

    @Test
    void workspaceCostBucketsNullUserAsUnattributed() {
        UUID wsId = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        // Agent-driven row with no user_email — typical for a Temporal
        // workflow that runs without an HTTP request context.
        service.emit(new ProvService.EmitRequest(
                pid, "agent", "background-task", "agent_narrative",
                null, "claude-sonnet-4-6", null, null,
                1000, 500, 0.0105, 800, null, null, null,
                wsId, null));

        var roll = service.workspaceCost(wsId,
                java.time.OffsetDateTime.now().minusDays(1),
                java.time.OffsetDateTime.now().plusDays(1));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byUser = (List<Map<String, Object>>) roll.get("byUser");
        assertThat(byUser).hasSize(1);
        assertThat(byUser.get(0).get("userEmail")).isEqualTo("(unattributed)");
    }

    @Test
    void workspaceCostExcludesRowsFromOtherWorkspaces() {
        UUID wsMine    = UUID.randomUUID();
        UUID wsTheirs  = UUID.randomUUID();
        emitWithCost(UUID.randomUUID(), wsMine,   "alice@x", "claude-sonnet-4-6", 100, 50, 0.005);
        emitWithCost(UUID.randomUUID(), wsTheirs, "bob@x",   "claude-opus-4-7",   100, 50, 0.050);

        var roll = service.workspaceCost(wsMine,
                java.time.OffsetDateTime.now().minusDays(1),
                java.time.OffsetDateTime.now().plusDays(1));

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) roll.get("totals");
        assertThat(((Number) totals.get("calls")).longValue()).isEqualTo(1L);
        // Confirms cross-tenant rows don't bleed into the rollup.
        assertThat(((java.math.BigDecimal) totals.get("costUsd")).doubleValue())
                .isCloseTo(0.005, org.assertj.core.data.Offset.offset(0.0002));
    }

    @Test
    void workspaceCostExcludesRowsOutsideTheRequestedWindow() {
        UUID wsId = UUID.randomUUID();
        UUID pid = UUID.randomUUID();
        emitWithCost(pid, wsId, "alice@x", "claude-sonnet-4-6", 100, 50, 0.005);

        // Future-only window should see zero calls.
        var roll = service.workspaceCost(wsId,
                java.time.OffsetDateTime.now().plusDays(7),
                java.time.OffsetDateTime.now().plusDays(8));

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) roll.get("totals");
        assertThat(((Number) totals.get("calls")).longValue()).isEqualTo(0L);
    }

    /* ---------------- helpers ---------------- */

    private void emit(UUID pid, String kind, String actor, String action) {
        service.emit(new ProvService.EmitRequest(
                pid, kind, actor, action,
                null, null, null, null, null, null, null, null, null, null));
    }

    private void emitWithCost(UUID pid, UUID wsId, String userEmail, String model,
                                int tokensIn, int tokensOut, double costUsd) {
        service.emit(new ProvService.EmitRequest(
                pid, "agent", "test-actor", "agent_narrative",
                null, model, null, null,
                tokensIn, tokensOut, costUsd, 100, null, null, null,
                wsId, userEmail));
    }
}
