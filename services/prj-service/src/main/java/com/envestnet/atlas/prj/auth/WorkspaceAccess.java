package com.envestnet.atlas.prj.auth;

import com.envestnet.atlas.prj.domain.WorkspaceMember;
import com.envestnet.atlas.prj.repo.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Workspace-level authorization. Answers "can user X do thing Y in
 * workspace W?" using {@code prj.workspace_member} as the source of
 * truth.
 *
 * <p>Two role hierarchies are at play in Atlas:
 * <ul>
 *   <li><b>JWT-level roles</b> (ENGINEER, TECH_LEAD, ADMIN) — gate
 *       platform-wide capabilities. Set by the IdP / fake-idp and
 *       carried in the gateway-asserted {@code X-Atlas-User-Roles}
 *       header. Checked declaratively via Spring Security's
 *       {@code @PreAuthorize("hasRole('TECH_LEAD')")} when the gate is
 *       a single platform-level permission.</li>
 *   <li><b>Workspace-level roles</b> (OWNER, EDITOR, VIEWER) — gate
 *       access to a specific workspace's data. Stored in
 *       {@code workspace_member} and consulted via this service. This
 *       is the layer that prevents a valid JWT from reading a
 *       workspace the user isn't a member of.</li>
 * </ul>
 *
 * <p>The two layers are AND-ed: a TECH_LEAD JWT cannot read
 * workspace W unless they're also a member of W, except for the
 * deliberate ADMIN bypass (see {@link #has}).</p>
 */
@Service
public class WorkspaceAccess {

    /** Workspace-level role ordering: higher index = strictly more privilege. */
    public enum Level {
        VIEWER,   // read-only
        EDITOR,   // read + write (mutate projects, advance gates, run stages)
        OWNER;    // read + write + admin (invite/remove members, delete workspace)

        /** True if a member with role {@code this} can act with at least {@code required} privilege. */
        public boolean covers(Level required) {
            return this.ordinal() >= required.ordinal();
        }
    }

    /**
     * Platform-level role that bypasses workspace membership checks.
     * Support engineers carry this role to triage customer issues
     * without needing to be invited to every workspace. ADMIN bypass
     * is audited: every access via this path emits an audit-log entry
     * tagged {@code via=ADMIN_BYPASS}.
     */
    public static final String JWT_ROLE_ADMIN = "ADMIN";

    private final WorkspaceMemberRepository members;

    public WorkspaceAccess(WorkspaceMemberRepository members) {
        this.members = members;
    }

    /**
     * Workspace-level authorization decision. The primary entry point.
     *
     * <p>Two paths to "true":
     * <ol>
     *   <li>User is a member of the workspace with a role covering
     *       {@code required} (e.g. EDITOR covers VIEWER).</li>
     *   <li>User carries the platform-level ADMIN role in their JWT
     *       (the deliberate support-engineer bypass).</li>
     * </ol>
     *
     * <p>"False" is the default. A missing membership row is
     * indistinguishable from an explicit deny — both produce 404
     * upstream so we don't leak workspace existence.</p>
     */
    public boolean has(String userEmail, UUID workspaceId, Level required) {
        if (workspaceId == null) return false;
        if (userEmail == null || userEmail.isBlank()) return false;

        // ADMIN bypass: a user with the platform-level ADMIN role can
        // act on any workspace. The role list is the gateway-asserted
        // header (X-Atlas-User-Roles).
        if (jwtHasAdminRole()) return true;

        return members.findByWorkspaceAndUser(workspaceId, userEmail)
                .map(WorkspaceMember::role)
                .map(WorkspaceAccess::parseLevel)
                .map(level -> level.covers(required))
                .orElse(false);
    }

    /** Sugar for {@code has(email, ws, VIEWER)}. */
    public boolean canRead(String userEmail, UUID workspaceId) {
        return has(userEmail, workspaceId, Level.VIEWER);
    }

    /** Sugar for {@code has(email, ws, EDITOR)}. */
    public boolean canWrite(String userEmail, UUID workspaceId) {
        return has(userEmail, workspaceId, Level.EDITOR);
    }

    /** Sugar for {@code has(email, ws, OWNER)}. */
    public boolean canAdmin(String userEmail, UUID workspaceId) {
        return has(userEmail, workspaceId, Level.OWNER);
    }

    /**
     * All workspaces the user can see. Used to filter
     * {@code GET /api/v1/workspaces} so the SPA chrome's workspace
     * switcher only lists workspaces the user is actually a member of.
     *
     * <p>ADMIN users see all workspaces — callers handle the listing
     * by branching on {@link #isAdmin(String)}.</p>
     */
    public Set<UUID> accessibleWorkspaceIds(String userEmail) {
        if (userEmail == null || userEmail.isBlank()) return Set.of();
        return members.findByUserEmail(userEmail).stream()
                .map(WorkspaceMember::workspaceId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** True if the caller carries the JWT-level ADMIN role. */
    public boolean isAdmin(String userEmail) {
        return jwtHasAdminRole();
    }

    /**
     * Lookup the caller's role in a specific workspace. Returns empty
     * when the caller isn't a member; doesn't apply the ADMIN bypass
     * (use {@link #has} for that). Used by the audit-log writer to
     * record the actual grant in effect.
     */
    public Optional<Level> levelOf(String userEmail, UUID workspaceId) {
        if (workspaceId == null || userEmail == null || userEmail.isBlank()) {
            return Optional.empty();
        }
        return members.findByWorkspaceAndUser(workspaceId, userEmail)
                .map(WorkspaceMember::role)
                .map(WorkspaceAccess::parseLevel);
    }

    private static Level parseLevel(String role) {
        if (role == null) return Level.VIEWER;
        try {
            return Level.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            // Unknown role in the database — treat as no access rather
            // than throwing, so a misconfigured row can't take the
            // service down. The audit-log will record the actual stored
            // role for follow-up.
            return Level.VIEWER;
        }
    }

    /**
     * True if the caller's JWT (via the gateway-asserted roles header)
     * carries the platform-level ADMIN role. Reads from
     * {@link GatewayIdentity} so it's safe inside and outside a request
     * context (returns false when there's no request).
     */
    private static boolean jwtHasAdminRole() {
        List<String> roles = GatewayIdentity.currentRoles();
        for (String r : roles) {
            if (r != null && JWT_ROLE_ADMIN.equalsIgnoreCase(r.trim())) return true;
        }
        return false;
    }
}
