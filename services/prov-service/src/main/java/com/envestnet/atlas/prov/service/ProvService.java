package com.envestnet.atlas.prov.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Array;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Append-only event store for every agent invocation, human resolution, and
 * artifact transformation across the platform. Other services POST events
 * here on a fire-and-forget basis; reads are filtered queries on the
 * (project_id, ts DESC) and (action) indexes already declared in init.sql.
 */
@Service
public class ProvService {
    private static final Logger log = LoggerFactory.getLogger(ProvService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public ProvService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UUID emit(EmitRequest req) {
        UUID id = UUID.randomUUID();
        try {
            // Resolve the trace id at write time. Order of precedence:
            //   1. Explicit value on the request (rare — backfill / replay)
            //   2. The current SLF4J MDC entry (set by Micrometer Tracing
            //      when a span is active for the inbound request)
            //   3. null (entry was written outside a span)
            String traceId = req.traceId();
            if (traceId == null || traceId.isBlank()) {
                traceId = org.slf4j.MDC.get("traceId");
            }
            // Phase 2L: prefer body-supplied workspace/user; fall back
            // to the per-request MDC so a caller that forgot to set the
            // body fields still gets attribution from the headers.
            UUID workspaceId = req.workspaceId();
            if (workspaceId == null) workspaceId = parseUuid(org.slf4j.MDC.get("workspaceId"));
            String userEmail = req.userEmail();
            if (userEmail == null || userEmail.isBlank()) userEmail = org.slf4j.MDC.get("userEmail");

            jdbc.update(
                "INSERT INTO prov.entry " +
                "(id, project_id, ts, actor_kind, actor_id, action, " +
                " prompt, model, output, tool_calls, " +
                " tokens_in, tokens_out, cost_usd, latency_ms, links, human_review, trace_id, " +
                " workspace_id, user_email) " +
                "VALUES (?, ?, now(), ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
                id,
                req.projectId(),
                req.actorKind() == null ? "system" : req.actorKind(),
                req.actorId() == null ? "unknown" : req.actorId(),
                req.action(),
                writeJson(req.prompt()),
                req.model(),
                writeJson(req.output()),
                writeJson(req.toolCalls()),
                req.tokensIn(),
                req.tokensOut(),
                req.costUsd(),
                req.latencyMs(),
                arrayOrNull(req.links()),
                writeJson(req.humanReview()),
                traceId,
                workspaceId,
                userEmail
            );
        } catch (Exception e) {
            log.warn("emit failed: {}", e.toString());
        }
        return id;
    }

    /* ---------------- cost rollups (Phase 2L) ---------------- */

    /**
     * Total + breakdown of cost across a workspace. Powers the
     * {@code GET /api/v1/workspaces/{ws}/cost} endpoint and the SPA's
     * Spend page.
     *
     * <p>Returns a map with:
     * <pre>
     *   {
     *     "workspaceId":   "...",
     *     "rangeStart":    "2026-04-11T00:00:00Z",  // inclusive
     *     "rangeEnd":      "2026-05-11T00:00:00Z",  // exclusive
     *     "totals":        { "calls": 1234, "tokensIn": ..., "tokensOut": ..., "costUsd": 42.17 },
     *     "byUser":        [{ "userEmail": "alice@...",   "calls": 87, "costUsd": 12.40 }, ...],
     *     "byProject":     [{ "projectId":  "uuid",        "calls": 50, "costUsd":  5.20 }, ...],
     *     "byModel":       [{ "model":      "claude-...",  "calls": 30, "costUsd": 18.00 }, ...],
     *     "daily":         [{ "day": "2026-05-01", "costUsd": 0.42 }, ...]
     *   }
     * </pre>
     */
    public Map<String, Object> workspaceCost(UUID workspaceId, OffsetDateTime from, OffsetDateTime to) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workspaceId", workspaceId.toString());
        out.put("rangeStart",  from.toString());
        out.put("rangeEnd",    to.toString());

        // Totals — single round trip, three coalesced sums.
        Map<String, Object> totals = jdbc.queryForMap(
            "SELECT count(*)::bigint AS calls, " +
            "       COALESCE(SUM(tokens_in),  0)::bigint   AS \"tokensIn\", " +
            "       COALESCE(SUM(tokens_out), 0)::bigint   AS \"tokensOut\", " +
            "       COALESCE(SUM(cost_usd),   0)::numeric  AS \"costUsd\" " +
            "FROM prov.entry " +
            "WHERE workspace_id = ? AND ts >= ? AND ts < ?",
            workspaceId, from, to);
        out.put("totals", totals);

        // Per-user — rows with null user_email collapse to "unattributed"
        // so the SPA can render the bucket explicitly rather than
        // dropping it silently.
        out.put("byUser", jdbc.query(
            "SELECT COALESCE(user_email, '(unattributed)') AS \"userEmail\", " +
            "       count(*)::bigint                       AS calls, " +
            "       COALESCE(SUM(cost_usd), 0)::numeric    AS \"costUsd\" " +
            "FROM prov.entry " +
            "WHERE workspace_id = ? AND ts >= ? AND ts < ? " +
            "GROUP BY COALESCE(user_email, '(unattributed)') " +
            "ORDER BY \"costUsd\" DESC, calls DESC " +
            "LIMIT 100",
            (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("userEmail", rs.getString("userEmail"));
                m.put("calls",     rs.getLong("calls"));
                m.put("costUsd",   rs.getBigDecimal("costUsd"));
                return m;
            },
            workspaceId, from, to));

        // Per-project — keyed by project_id, easy to join client-side
        // against the project list for friendly names.
        out.put("byProject", jdbc.query(
            "SELECT project_id                            AS \"projectId\", " +
            "       count(*)::bigint                       AS calls, " +
            "       COALESCE(SUM(cost_usd), 0)::numeric    AS \"costUsd\" " +
            "FROM prov.entry " +
            "WHERE workspace_id = ? AND ts >= ? AND ts < ? " +
            "GROUP BY project_id " +
            "ORDER BY \"costUsd\" DESC, calls DESC " +
            "LIMIT 100",
            (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("projectId", rs.getObject("projectId"));
                m.put("calls",     rs.getLong("calls"));
                m.put("costUsd",   rs.getBigDecimal("costUsd"));
                return m;
            },
            workspaceId, from, to));

        // Per-model — answers "did a Sonnet→Opus switch cause the spike?"
        out.put("byModel", jdbc.query(
            "SELECT COALESCE(model, '(unknown)')          AS model, " +
            "       count(*)::bigint                       AS calls, " +
            "       COALESCE(SUM(cost_usd), 0)::numeric    AS \"costUsd\" " +
            "FROM prov.entry " +
            "WHERE workspace_id = ? AND ts >= ? AND ts < ? " +
            "GROUP BY COALESCE(model, '(unknown)') " +
            "ORDER BY \"costUsd\" DESC, calls DESC",
            (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("model",   rs.getString("model"));
                m.put("calls",   rs.getLong("calls"));
                m.put("costUsd", rs.getBigDecimal("costUsd"));
                return m;
            },
            workspaceId, from, to));

        // Daily sparkline — UTC day buckets, ordered ascending so the
        // SPA can render left-to-right without sorting.
        out.put("daily", jdbc.query(
            "SELECT date_trunc('day', ts AT TIME ZONE 'UTC')::date AS day, " +
            "       count(*)::bigint                                AS calls, " +
            "       COALESCE(SUM(cost_usd), 0)::numeric             AS \"costUsd\" " +
            "FROM prov.entry " +
            "WHERE workspace_id = ? AND ts >= ? AND ts < ? " +
            "GROUP BY day " +
            "ORDER BY day ASC",
            (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("day",     rs.getDate("day").toString());
                m.put("calls",   rs.getLong("calls"));
                m.put("costUsd", rs.getBigDecimal("costUsd"));
                return m;
            },
            workspaceId, from, to));

        return out;
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s.trim()); }
        catch (IllegalArgumentException e) { return null; }
    }

    public List<Map<String, Object>> query(UUID projectId, Filter f, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("project_id = ?");
        args.add(projectId);

        if (f.action != null && !f.action.isBlank()) {
            where.append(" AND action = ?"); args.add(f.action);
        }
        if (f.actorKind != null && !f.actorKind.isBlank()) {
            where.append(" AND actor_kind = ?"); args.add(f.actorKind);
        }
        if (f.actorId != null && !f.actorId.isBlank()) {
            where.append(" AND actor_id = ?"); args.add(f.actorId);
        }
        if (f.q != null && !f.q.isBlank()) {
            where.append(" AND (action ILIKE ? OR actor_id ILIKE ? OR cast(output as text) ILIKE ?)");
            String pat = "%" + f.q + "%";
            args.add(pat); args.add(pat); args.add(pat);
        }

        String sql =
            "SELECT id, project_id, ts, actor_kind, actor_id, action, " +
            "       prompt, model, output, tool_calls, " +
            "       tokens_in, tokens_out, cost_usd, latency_ms, links, human_review, trace_id " +
            "FROM prov.entry WHERE " + where +
            " ORDER BY ts DESC LIMIT " + Math.min(Math.max(limit, 1), 1000);

        return jdbc.query(sql, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getObject("id"));
            m.put("projectId", rs.getObject("project_id"));
            m.put("ts", rs.getObject("ts", OffsetDateTime.class));
            m.put("actorKind", rs.getString("actor_kind"));
            m.put("actorId", rs.getString("actor_id"));
            m.put("action", rs.getString("action"));
            m.put("prompt", parseJson(rs.getString("prompt")));
            m.put("model", rs.getString("model"));
            m.put("output", parseJson(rs.getString("output")));
            m.put("toolCalls", parseJson(rs.getString("tool_calls")));
            m.put("tokensIn", (Object) rs.getObject("tokens_in"));
            m.put("tokensOut", (Object) rs.getObject("tokens_out"));
            m.put("costUsd", (Object) rs.getObject("cost_usd"));
            m.put("latencyMs", (Object) rs.getObject("latency_ms"));
            try {
                Array arr = rs.getArray("links");
                m.put("links", arr == null ? List.of() : Arrays.asList((Object[]) arr.getArray()));
            } catch (Exception e) { m.put("links", List.of()); }
            m.put("humanReview", parseJson(rs.getString("human_review")));
            m.put("traceId", rs.getString("trace_id"));
            return m;
        }, args.toArray());
    }

    public Map<String, Object> aggregate(UUID projectId) {
        Map<String, Object> out = new LinkedHashMap<>();

        Long total = jdbc.queryForObject(
            "SELECT count(*) FROM prov.entry WHERE project_id = ?", Long.class, projectId);
        out.put("total", total == null ? 0L : total);

        out.put("byActor", jdbc.query(
            "SELECT actor_kind, count(*) AS c FROM prov.entry WHERE project_id = ? " +
            "GROUP BY actor_kind ORDER BY c DESC",
            (rs, i) -> Map.of(
                    "kind", rs.getString("actor_kind"),
                    "count", rs.getLong("c")),
            projectId));

        out.put("byAction", jdbc.query(
            "SELECT action, count(*) AS c FROM prov.entry WHERE project_id = ? " +
            "GROUP BY action ORDER BY c DESC LIMIT 20",
            (rs, i) -> Map.of(
                    "action", rs.getString("action"),
                    "count", rs.getLong("c")),
            projectId));

        out.put("tokens", jdbc.queryForMap(
            "SELECT COALESCE(SUM(tokens_in),  0)::bigint AS \"in\", " +
            "       COALESCE(SUM(tokens_out), 0)::bigint AS \"out\", " +
            "       COALESCE(SUM(cost_usd),   0)::numeric  AS cost " +
            "FROM prov.entry WHERE project_id = ?",
            projectId));

        return out;
    }

    public byte[] exportCsv(UUID projectId) {
        StringBuilder sb = new StringBuilder();
        sb.append("ts,actor_kind,actor_id,action,model,tokens_in,tokens_out,cost_usd,latency_ms\n");
        jdbc.query(
            "SELECT ts, actor_kind, actor_id, action, model, " +
            "       tokens_in, tokens_out, cost_usd, latency_ms " +
            "FROM prov.entry WHERE project_id = ? ORDER BY ts ASC",
            (rs) -> {
                sb.append(rs.getObject("ts")).append(',')
                  .append(rs.getString("actor_kind")).append(',')
                  .append(csvField(rs.getString("actor_id"))).append(',')
                  .append(csvField(rs.getString("action"))).append(',')
                  .append(csvField(rs.getString("model"))).append(',')
                  .append(rs.getInt("tokens_in")).append(',')
                  .append(rs.getInt("tokens_out")).append(',')
                  .append(rs.getBigDecimal("cost_usd") == null ? "" : rs.getBigDecimal("cost_usd")).append(',')
                  .append(rs.getInt("latency_ms")).append('\n');
            }, projectId);
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /* ---------------- helpers ---------------- */

    private String writeJson(Object o) {
        if (o == null) return null;
        try { return json.writeValueAsString(o); } catch (Exception e) { return null; }
    }
    private Object parseJson(String s) {
        if (s == null) return null;
        try { return json.readValue(s, Object.class); } catch (Exception e) { return s; }
    }
    private static String csvField(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
    private String[] arrayOrNull(List<String> links) {
        return links == null ? null : links.toArray(new String[0]);
    }

    /* ---------------- DTOs ---------------- */

    public record EmitRequest(
            UUID projectId,
            String actorKind,           // agent | human | system
            String actorId,             // e.g. code-archaeology, dev@envestnet.local
            String action,              // e.g. agent_narrative, decision_resolved, bundle_built
            Object prompt,              // any structured shape
            String model,
            Object output,
            Object toolCalls,
            Integer tokensIn,
            Integer tokensOut,
            Double costUsd,
            Integer latencyMs,
            List<String> links,         // referenced artifact ids (uuid strings, paths, etc.)
            Object humanReview,
            String traceId,             // OTel trace id; null lets emit() fall back to MDC
            // Phase 2L — cost attribution dimensions. Both nullable; the
            // emitter falls back to MDC values when missing. Existing
            // callers can keep using the older constructor below.
            UUID workspaceId,
            String userEmail
    ) {
        /** Pre-2L constructor — emit() will pull workspace + user from MDC. */
        public EmitRequest(
                UUID projectId, String actorKind, String actorId, String action,
                Object prompt, String model, Object output, Object toolCalls,
                Integer tokensIn, Integer tokensOut, Double costUsd, Integer latencyMs,
                List<String> links, Object humanReview, String traceId) {
            this(projectId, actorKind, actorId, action, prompt, model, output, toolCalls,
                 tokensIn, tokensOut, costUsd, latencyMs, links, humanReview, traceId,
                 null, null);
        }

        /** Backwards-compat constructor for callers that pre-date the trace_id column. */
        public EmitRequest(
                UUID projectId, String actorKind, String actorId, String action,
                Object prompt, String model, Object output, Object toolCalls,
                Integer tokensIn, Integer tokensOut, Double costUsd, Integer latencyMs,
                List<String> links, Object humanReview) {
            this(projectId, actorKind, actorId, action, prompt, model, output, toolCalls,
                 tokensIn, tokensOut, costUsd, latencyMs, links, humanReview, null);
        }
    }

    public static class Filter {
        public String action;
        public String actorKind;
        public String actorId;
        public String q;
    }
}
