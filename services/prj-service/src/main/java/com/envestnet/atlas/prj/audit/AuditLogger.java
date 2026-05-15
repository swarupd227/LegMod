package com.envestnet.atlas.prj.audit;

import com.envestnet.atlas.prj.auth.GatewayIdentity;
import com.envestnet.atlas.prj.auth.WorkspaceAccess;
import com.envestnet.atlas.prj.logging.RequestContextFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes append-only entries to {@code prj.audit_log}.
 *
 * <p>The audit table is jsonb-flexible on the payload, so writes go
 * through a native {@code JdbcTemplate} call rather than Spring Data
 * JDBC's repository abstraction — that way the payload is real JSON
 * in Postgres, not an escaped String. The same pattern is used by
 * prov-service for its own provenance writes.</p>
 *
 * <p>Failure to write an audit entry must never break the request
 * being audited (an outage of the audit table is bad, but losing the
 * primary work would be worse). The writer logs at WARN and returns
 * normally on failure; downstream observability is responsible for
 * alerting if audit rows stop arriving.</p>
 */
@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    /**
     * Pseudo-role recorded when the actor is acting via the platform
     * ADMIN-role bypass rather than a real workspace membership. Set
     * by {@link WorkspaceAccess#isAdmin(String)}.
     */
    public static final String ROLE_ADMIN_BYPASS = "ADMIN_BYPASS";
    /** Pseudo-role recorded when no gateway identity was present. */
    public static final String ROLE_UNAUTHENTICATED = "unauthenticated";

    private final JdbcTemplate jdbc;
    private final WorkspaceAccess access;
    private final ObjectMapper mapper;

    public AuditLogger(JdbcTemplate jdbc, WorkspaceAccess access, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.access = access;
        this.mapper = mapper;
    }

    /**
     * Record a successful action. Callers pass the workspace + project
     * context (already resolved by the controller's auth helpers) plus
     * a human-readable action discriminator (dot-separated namespace,
     * e.g. {@code "gate.advance"}) and an arbitrary payload that gets
     * stored as jsonb.
     */
    public void record(UUID workspaceId, UUID projectId, String action,
                       String entityType, String entityId,
                       Map<String, Object> payload) {
        write(workspaceId, projectId, action, entityType, entityId, "success", payload);
    }

    /**
     * Record a failed action — the controller caught an exception or
     * the downstream returned an error. Payload should include enough
     * context to investigate (status code, error message preview).
     */
    public void recordFailure(UUID workspaceId, UUID projectId, String action,
                                String entityType, String entityId,
                                Map<String, Object> payload) {
        write(workspaceId, projectId, action, entityType, entityId, "failure", payload);
    }

    private void write(UUID workspaceId, UUID projectId, String action,
                        String entityType, String entityId, String outcome,
                        Map<String, Object> payload) {
        String email = GatewayIdentity.currentEmail().orElse(null);
        String role  = resolveRole(email, workspaceId);
        String requestId = MDC.get(RequestContextFilter.MDC_REQUEST_ID);

        // Defensive: never let a missing optional crash the audit write.
        Map<String, Object> safePayload = payload == null
                ? new LinkedHashMap<>()
                : payload;
        String payloadJson;
        try {
            payloadJson = mapper.writeValueAsString(safePayload);
        } catch (Exception e) {
            // Don't lose the audit row over a Jackson hiccup — fall back
            // to a stub payload that still records "an entry was here."
            payloadJson = "{\"_serialization_error\":\""
                    + e.getClass().getSimpleName() + "\"}";
        }
        final String finalPayloadJson = payloadJson;

        try {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO prj.audit_log " +
                        " (request_id, workspace_id, project_id, user_email, user_role, " +
                        "  action, entity_type, entity_id, outcome, payload) " +
                        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)");
                setNullableString(ps, 1, requestId);
                setNullableObject(ps, 2, workspaceId, Types.OTHER);
                setNullableObject(ps, 3, projectId,   Types.OTHER);
                setNullableString(ps, 4, email);
                setNullableString(ps, 5, role);
                ps.setString(6, action);
                setNullableString(ps, 7, entityType);
                setNullableString(ps, 8, entityId);
                ps.setString(9, outcome);
                ps.setString(10, finalPayloadJson);
                return ps;
            });
        } catch (Exception e) {
            // Audit failures must not break the request being audited.
            // Surface at WARN with enough context to investigate; the
            // observability stack (Phase 2C) will alert on an absence
            // of rows downstream.
            log.warn("audit_log write failed for action={} workspace={} project={} actor={}",
                    action, workspaceId, projectId, email, e);
        }
    }

    /**
     * Determine the actor's effective role for the audit row. Three cases:
     * <ul>
     *   <li>No email → unauthenticated (rare in production; would only
     *       happen for the {@code /internal/seed} endpoint and similar).</li>
     *   <li>Platform-level ADMIN role → recorded as {@link #ROLE_ADMIN_BYPASS}
     *       so investigations can tell apart "support engineer doing
     *       triage" from "user is a real member."</li>
     *   <li>Otherwise → the workspace-level role from
     *       {@code workspace_member}, or {@code unauthenticated} when
     *       the user has no membership (shouldn't happen if the
     *       controller's access check passed — but recorded for
     *       defense-in-depth).</li>
     * </ul>
     */
    private String resolveRole(String email, UUID workspaceId) {
        if (email == null || email.isBlank()) return ROLE_UNAUTHENTICATED;
        if (access.isAdmin(email)) return ROLE_ADMIN_BYPASS;
        return access.levelOf(email, workspaceId)
                .map(Enum::name)
                .orElse(ROLE_UNAUTHENTICATED);
    }

    private static void setNullableString(PreparedStatement ps, int idx, String v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.VARCHAR);
        else ps.setString(idx, v);
    }

    private static void setNullableObject(PreparedStatement ps, int idx, Object v, int sqlType) throws SQLException {
        if (v == null) ps.setNull(idx, sqlType);
        else ps.setObject(idx, v, sqlType);
    }
}
