package com.envestnet.atlas.prj.auth;

import com.envestnet.atlas.prj.domain.WorkspaceMember;
import com.envestnet.atlas.prj.repo.WorkspaceMemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-unit coverage of {@link WorkspaceAccess}.
 *
 * <p>The service has three observable behaviours that the rest of the
 * platform relies on:
 *  • Role hierarchy — OWNER covers EDITOR covers VIEWER.
 *  • ADMIN bypass — the platform-level ADMIN JWT role grants access
 *    to every workspace, recorded separately so audits can tell apart
 *    "support engineer triaging" from "user is a real member".
 *  • Defensive defaults — null / blank emails, unknown roles in the
 *    database, etc. degrade to "no access" rather than crashing.</p>
 */
class WorkspaceAccessTest {

    private final WorkspaceMemberRepository members = mock(WorkspaceMemberRepository.class);
    private final WorkspaceAccess access = new WorkspaceAccess(members);

    private final UUID WS = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /* ---------------- role hierarchy ---------------- */

    @Test
    void ownerCoversEveryLowerLevel() {
        bindRequestWithRoles("ENGINEER");
        stubMembership("alice@envestnet.local", "OWNER");

        assertThat(access.canRead ("alice@envestnet.local", WS)).isTrue();
        assertThat(access.canWrite("alice@envestnet.local", WS)).isTrue();
        assertThat(access.canAdmin("alice@envestnet.local", WS)).isTrue();
    }

    @Test
    void editorCoversReadAndWriteButNotAdmin() {
        bindRequestWithRoles("ENGINEER");
        stubMembership("bob@envestnet.local", "EDITOR");

        assertThat(access.canRead ("bob@envestnet.local", WS)).isTrue();
        assertThat(access.canWrite("bob@envestnet.local", WS)).isTrue();
        assertThat(access.canAdmin("bob@envestnet.local", WS)).isFalse();
    }

    @Test
    void viewerCanOnlyRead() {
        bindRequestWithRoles("ENGINEER");
        stubMembership("carol@envestnet.local", "VIEWER");

        assertThat(access.canRead ("carol@envestnet.local", WS)).isTrue();
        assertThat(access.canWrite("carol@envestnet.local", WS)).isFalse();
        assertThat(access.canAdmin("carol@envestnet.local", WS)).isFalse();
    }

    @Test
    void nonMemberIsDeniedAtEveryLevel() {
        bindRequestWithRoles("ENGINEER");
        when(members.findByWorkspaceAndUser(eq(WS), eq("intruder@example.com")))
                .thenReturn(Optional.empty());

        assertThat(access.canRead ("intruder@example.com", WS)).isFalse();
        assertThat(access.canWrite("intruder@example.com", WS)).isFalse();
        assertThat(access.canAdmin("intruder@example.com", WS)).isFalse();
    }

    /* ---------------- ADMIN bypass ---------------- */

    @Test
    void platformAdminRoleBypassesMembershipCheck() {
        // The user has NO membership row for this workspace, but their
        // JWT carries the platform-level ADMIN role — bypass applies.
        bindRequestWithRoles("ENGINEER,ADMIN");
        when(members.findByWorkspaceAndUser(eq(WS), eq("support@envestnet.local")))
                .thenReturn(Optional.empty());

        assertThat(access.canRead ("support@envestnet.local", WS)).isTrue();
        assertThat(access.canWrite("support@envestnet.local", WS)).isTrue();
        assertThat(access.canAdmin("support@envestnet.local", WS)).isTrue();
        assertThat(access.isAdmin("support@envestnet.local")).isTrue();
    }

    @Test
    void adminRoleCheckIsCaseInsensitive() {
        // Real-world IdPs (Keycloak, Okta) emit role names in mixed
        // case. Match without bias.
        bindRequestWithRoles("Admin");
        assertThat(access.isAdmin("support@envestnet.local")).isTrue();
    }

    @Test
    void levelOfReturnsTheRawMembershipRoleNotTheAdminBypass() {
        // levelOf is the AUDIT layer's window into the actual stored
        // role — the ADMIN-bypass status is recorded separately by
        // AuditLogger. So levelOf must NOT collapse the two.
        bindRequestWithRoles("ADMIN");
        stubMembership("alice@envestnet.local", "VIEWER");

        assertThat(access.levelOf("alice@envestnet.local", WS))
                .contains(WorkspaceAccess.Level.VIEWER);
    }

    /* ---------------- accessibleWorkspaceIds ---------------- */

    @Test
    void accessibleWorkspaceIdsReflectsMembershipRows() {
        UUID ws1 = UUID.randomUUID();
        UUID ws2 = UUID.randomUUID();
        when(members.findByUserEmail("dan@envestnet.local")).thenReturn(List.of(
                new WorkspaceMember(UUID.randomUUID(), ws1, "dan@envestnet.local",
                        "EDITOR", OffsetDateTime.now(), null),
                new WorkspaceMember(UUID.randomUUID(), ws2, "dan@envestnet.local",
                        "VIEWER", OffsetDateTime.now(), null)));

        Set<UUID> ids = access.accessibleWorkspaceIds("dan@envestnet.local");

        assertThat(ids).containsExactlyInAnyOrder(ws1, ws2);
    }

    @Test
    void accessibleWorkspaceIdsIsEmptyForUnknownUser() {
        when(members.findByUserEmail("ghost@nowhere")).thenReturn(List.of());
        assertThat(access.accessibleWorkspaceIds("ghost@nowhere")).isEmpty();
    }

    /* ---------------- defensive behaviour ---------------- */

    @Test
    void nullOrBlankInputsReturnFalseRatherThanThrowing() {
        // The auth check sits in the request hot path; a thrown
        // exception would 500 every request that happens to arrive with
        // a missing field. Degrade to "no access" instead.
        assertThat(access.canRead(null,    WS)).isFalse();
        assertThat(access.canRead("",      WS)).isFalse();
        assertThat(access.canRead(" ",     WS)).isFalse();
        assertThat(access.canRead("alice", null)).isFalse();
    }

    @Test
    void unknownRoleInDatabaseTreatsAsLowestPrivilege() {
        // A stale or misconfigured row with role='SUPERUSER' must not
        // grant access — the service can't unilaterally invent privilege.
        bindRequestWithRoles("ENGINEER");
        when(members.findByWorkspaceAndUser(eq(WS), eq("alice@envestnet.local")))
                .thenReturn(Optional.of(new WorkspaceMember(
                        UUID.randomUUID(), WS, "alice@envestnet.local",
                        "SUPERUSER", OffsetDateTime.now(), null)));

        // Unknown role degrades to VIEWER (lowest) — neither write nor admin.
        assertThat(access.canRead ("alice@envestnet.local", WS)).isTrue();
        assertThat(access.canWrite("alice@envestnet.local", WS)).isFalse();
        assertThat(access.canAdmin("alice@envestnet.local", WS)).isFalse();
    }

    /* ---------------- helpers ---------------- */

    private void stubMembership(String email, String role) {
        when(members.findByWorkspaceAndUser(eq(WS), eq(email)))
                .thenReturn(Optional.of(new WorkspaceMember(
                        UUID.randomUUID(), WS, email, role,
                        OffsetDateTime.now(), null)));
    }

    /** Bind a synthetic request so {@code GatewayIdentity.currentRoles()} can read it. */
    private void bindRequestWithRoles(String rolesCsv) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(GatewayIdentity.HDR_ROLES, rolesCsv);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }
}
