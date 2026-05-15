package com.envestnet.atlas.prj.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps a user (by email) to a workspace with a role.
 *
 * <p>The grant model is intentionally small: one row per (workspace, user)
 * with a single role. There is no nested group / team concept yet — when
 * a customer needs that we'll layer it on top, not replace this table.</p>
 *
 * <p>Role hierarchy is OWNER &gt; EDITOR &gt; VIEWER. Each strictly
 * subsumes the rights of the levels below it; the check in
 * {@code WorkspaceAccess} reads the role and compares against a required
 * minimum.</p>
 */
@Table(schema = "prj", value = "workspace_member")
public record WorkspaceMember(
        @Id UUID id,
        UUID workspaceId,
        String userEmail,
        String role,             // OWNER | EDITOR | VIEWER
        OffsetDateTime grantedAt,
        String grantedBy
) {}
