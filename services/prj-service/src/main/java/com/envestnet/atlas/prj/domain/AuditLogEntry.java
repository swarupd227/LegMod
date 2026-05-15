package com.envestnet.atlas.prj.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Append-only record of a state-changing operation.
 *
 * <p>{@code payload} is a {@code jsonb} column with a server-side
 * default of {@code '{}'::jsonb}. It's marked {@link ReadOnlyProperty}
 * so Spring Data JDBC doesn't try to write the Java String into a
 * jsonb column — same pattern as {@code Project.metrics}. The
 * {@link com.envestnet.atlas.prj.audit.AuditLogger} writes payloads
 * via a separate native query that round-trips through Jackson →
 * jsonb so consumers see real JSON, not an escaped string.</p>
 */
@Table(schema = "prj", value = "audit_log")
public record AuditLogEntry(
        @Id UUID id,
        OffsetDateTime ts,
        String requestId,
        UUID workspaceId,
        UUID projectId,
        String userEmail,
        String userRole,    // OWNER | EDITOR | VIEWER | ADMIN_BYPASS | unauthenticated
        String action,      // project.create | gate.advance | recipe.decision | ...
        String entityType,
        String entityId,
        String outcome,     // success | failure
        @ReadOnlyProperty String payload
) {}
