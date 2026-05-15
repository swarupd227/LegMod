package com.envestnet.atlas.prj.web;

import com.envestnet.atlas.prj.audit.AuditLogger;
import com.envestnet.atlas.prj.auth.GatewayIdentity;
import com.envestnet.atlas.prj.auth.WorkspaceAccess;
import com.envestnet.atlas.prj.auth.WorkspaceAccessDeniedException;
import com.envestnet.atlas.prj.domain.Gate;
import com.envestnet.atlas.prj.domain.Project;
import com.envestnet.atlas.prj.domain.WorkspaceMember;
import com.envestnet.atlas.prj.repo.GateRepository;
import com.envestnet.atlas.prj.repo.ProjectRepository;
import com.envestnet.atlas.prj.repo.WorkspaceMemberRepository;
import com.envestnet.atlas.prj.repo.WorkspaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 2K — cross-tenant authorization coverage for {@link ProjectController}.
 *
 * <p>The existing {@link ProjectControllerTest} pins the stage / gate
 * state machine; every test there assumes the caller is OWNER of the
 * project's workspace. This sister suite focuses exclusively on the
 * authorization layer:
 *
 *  • A non-member can't see or mutate projects in workspaces they're
 *    not a member of (404, not 403 — never leak existence).
 *  • A VIEWER can read but writes get a 403.
 *  • The platform ADMIN role bypasses membership.
 *  • The /workspaces listing only returns accessible workspaces.
 *  • createProject 403s on a workspace the caller isn't an EDITOR of.
 *
 * <p>These tests use synthetic UUIDs to model two separate workspaces
 * (WS_OWN and WS_OTHER) and a project located in each, with a single
 * caller (alice) who's a VIEWER of WS_OWN and a non-member of
 * WS_OTHER. The controller's response shape is the contract: tests
 * pin the status code + the absence of side effects on deny.</p>
 */
class ProjectControllerAuthorizationTest {

    private static final UUID WS_OWN   = UUID.fromString("aaaa1111-0000-0000-0000-000000000001");
    private static final UUID WS_OTHER = UUID.fromString("bbbb2222-0000-0000-0000-000000000002");
    private static final UUID PROJECT_OWN   = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROJECT_OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String ALICE = "alice@envestnet.local";

    private WorkspaceRepository workspaces;
    private WorkspaceMemberRepository memberRepo;
    private ProjectRepository projects;
    private GateRepository gates;
    private RestTemplate http;
    private AuditLogger audit;
    private ProjectController controller;

    @BeforeEach
    void setUp() {
        workspaces = mock(WorkspaceRepository.class);
        memberRepo = mock(WorkspaceMemberRepository.class);
        projects   = mock(ProjectRepository.class);
        gates      = mock(GateRepository.class);
        http       = mock(RestTemplate.class);
        audit      = mock(AuditLogger.class);

        // alice is a VIEWER of WS_OWN, not a member of WS_OTHER.
        when(memberRepo.findByWorkspaceAndUser(eq(WS_OWN), eq(ALICE)))
                .thenReturn(Optional.of(new WorkspaceMember(
                        UUID.randomUUID(), WS_OWN, ALICE, "VIEWER",
                        OffsetDateTime.now(), null)));
        when(memberRepo.findByWorkspaceAndUser(eq(WS_OTHER), eq(ALICE)))
                .thenReturn(Optional.empty());
        // Projects are mapped to their workspaces.
        when(projects.findById(PROJECT_OWN))
                .thenReturn(Optional.of(project(PROJECT_OWN, WS_OWN)));
        when(projects.findById(PROJECT_OTHER))
                .thenReturn(Optional.of(project(PROJECT_OTHER, WS_OTHER)));

        var metrics = new com.envestnet.atlas.prj.metrics.AtlasMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var workflowClient = mock(io.temporal.client.WorkflowClient.class);

        controller = new ProjectController(
                workspaces, memberRepo, projects, gates,
                new WorkspaceAccess(memberRepo), audit, http, metrics, workflowClient,
                "http://arch", "http://cap", "http://recon", "http://gen",
                "http://diff", "http://reports", "http://prov", "http://uplift");

        // Default: alice is the caller. Per-test code overrides identity
        // when needed (admin bypass, anonymous).
        bindIdentity(ALICE, "ENGINEER");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        org.slf4j.MDC.clear();
    }

    /* ---------------- read of foreign project is 404 ---------------- */

    @Test
    void getProjectInAnotherWorkspaceReturns404NotForbidden() {
        // Critical: 404, not 403. A probing caller must not be able to
        // distinguish "no such project" from "you can't see it" so they
        // can't enumerate which UUIDs map to real projects in other
        // tenants.
        ResponseEntity<?> resp = controller.getProject(PROJECT_OTHER);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void getProjectInMembershipWorkspaceReturns200() {
        ResponseEntity<?> resp = controller.getProject(PROJECT_OWN);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    /* ---------------- write of foreign project is 404 ---------------- */

    @Test
    void mutatingAForeignProjectReturns404AndPerformsNoWrites() {
        ResponseEntity<?> resp = controller.attachSource(
                PROJECT_OTHER,
                new ProjectController.AttachSourceRequest("/some/path"));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        verify(projects, never()).save(any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void runInventoryOnForeignProjectReturns404WithoutDownstreamCall() {
        // Stage triggers that fan out to other services must NOT hit
        // the downstream when authorization denies — otherwise a
        // probing caller leaks information through timing or rate
        // signals at the downstream.
        ResponseEntity<?> resp = controller.runInventory(PROJECT_OTHER);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(http);
    }

    /* ---------------- viewer can read but not write ---------------- */

    @Test
    void viewerOnOwnWorkspaceCanReadProject() {
        ResponseEntity<?> resp = controller.getProject(PROJECT_OWN);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void viewerOnOwnWorkspaceGets403OnWrite() {
        // alice is VIEWER of WS_OWN — she can SEE the project but not
        // mutate it. The path doesn't leak existence (she already knows
        // the project exists), so 403 is the right status.
        assertThatThrownBy(() -> controller.attachSource(
                        PROJECT_OWN,
                        new ProjectController.AttachSourceRequest("/some/path")))
                .isInstanceOf(WorkspaceAccessDeniedException.class);

        verify(projects, never()).save(any());
    }

    /* ---------------- ADMIN bypass ---------------- */

    @Test
    void adminRoleBypassesWorkspaceMembership() {
        // Same alice@envestnet.local — now carrying the platform ADMIN
        // role. She becomes able to read projects in workspaces she has
        // no membership for.
        bindIdentity(ALICE, "ENGINEER,ADMIN");

        ResponseEntity<?> resp = controller.getProject(PROJECT_OTHER);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    /* ---------------- listWorkspaces is per-user filtered ---------------- */

    @Test
    void listWorkspacesReturnsOnlyWorkspacesTheCallerIsAMemberOf() {
        when(memberRepo.findByUserEmail(ALICE)).thenReturn(List.of(
                new WorkspaceMember(UUID.randomUUID(), WS_OWN, ALICE, "VIEWER",
                        OffsetDateTime.now(), null)));
        when(workspaces.findAllById(eq(java.util.Set.of(WS_OWN))))
                .thenReturn(List.of(
                        new com.envestnet.atlas.prj.domain.Workspace(
                                WS_OWN, "Mine", ALICE, OffsetDateTime.now())));

        var result = controller.listWorkspaces();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(WS_OWN);
        // Crucially: the full table (which would include WS_OTHER) was
        // never requested.
        verify(workspaces, never()).findAll();
    }

    @Test
    void listWorkspacesReturnsAllForAdmin() {
        bindIdentity(ALICE, "ADMIN");
        var wsOwn = new com.envestnet.atlas.prj.domain.Workspace(
                WS_OWN, "Mine", ALICE, OffsetDateTime.now());
        var wsOther = new com.envestnet.atlas.prj.domain.Workspace(
                WS_OTHER, "Theirs", "carol@envestnet.local", OffsetDateTime.now());
        when(workspaces.findAll()).thenReturn(List.of(wsOwn, wsOther));

        var result = controller.listWorkspaces();

        assertThat(result).extracting(w -> w.id())
                .containsExactlyInAnyOrder(WS_OWN, WS_OTHER);
        // Membership table was NOT consulted — admins skip the
        // per-user accessible-set query.
        verify(memberRepo, never()).findByUserEmail(any());
    }

    /* ---------------- listProjects honors workspace membership ---------------- */

    @Test
    void listProjectsByWorkspaceReturns404WhenCallerIsNotAMember() {
        ResponseEntity<?> resp = controller.listProjects(WS_OTHER);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        verify(projects, never()).findByWorkspace(any());
    }

    @Test
    void listProjectsByWorkspaceReturnsProjectsWhenCallerIsAMember() {
        when(projects.findByWorkspace(WS_OWN))
                .thenReturn(List.of(project(PROJECT_OWN, WS_OWN)));

        ResponseEntity<List<Map<String, Object>>> resp = controller.listProjects(WS_OWN);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).hasSize(1);
    }

    /* ---------------- createProject requires EDITOR ---------------- */

    @Test
    void createProjectAsViewerOnTargetWorkspaceReturns403() {
        // alice is only a VIEWER of WS_OWN. createProject needs EDITOR+.
        var req = new ProjectController.CreateProjectRequest(
                WS_OWN, "Demo", "desc", "SOAP",
                "Axis", "Spring", "21", "Broadridge", "alice", "MEDIUM",
                "/legacy");

        assertThatThrownBy(() -> controller.createProject(req))
                .isInstanceOf(WorkspaceAccessDeniedException.class);

        verify(projects, never()).save(any());
        verify(gates,    never()).save(any());
    }

    @Test
    void createProjectAsEditorSucceeds() {
        // Upgrade alice to EDITOR for this test.
        when(memberRepo.findByWorkspaceAndUser(eq(WS_OWN), eq(ALICE)))
                .thenReturn(Optional.of(new WorkspaceMember(
                        UUID.randomUUID(), WS_OWN, ALICE, "EDITOR",
                        OffsetDateTime.now(), null)));
        when(projects.save(any())).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            return new Project(UUID.randomUUID(), p.workspaceId(), p.name(),
                    p.description(), p.mode(), p.sourceFramework(),
                    p.targetFramework(), p.targetJavaVersion(), p.vendorPartner(),
                    p.owner(), p.riskTier(), p.currentStage(), p.sourcePath(),
                    p.metrics(), p.createdAt(), p.updatedAt());
        });
        when(gates.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var req = new ProjectController.CreateProjectRequest(
                WS_OWN, "Demo", "desc", "SOAP",
                "Axis", "Spring", "21", "Broadridge", "alice", "MEDIUM",
                "/legacy");

        ResponseEntity<?> resp = controller.createProject(req);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(projects).save(any());
    }

    /* ---------------- helpers ---------------- */

    private static Project project(UUID id, UUID workspaceId) {
        OffsetDateTime now = OffsetDateTime.now();
        return new Project(
                id, workspaceId, "Demo Project", "desc", "SOAP",
                "Axis 1.4", "Spring Boot 3.3", "21", "Broadridge",
                "alice@envestnet.local", "MEDIUM", "A", "/legacy",
                "{}", now, now);
    }

    private static void bindIdentity(String email, String rolesCsv) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(GatewayIdentity.HDR_EMAIL, email);
        req.addHeader(GatewayIdentity.HDR_ROLES, rolesCsv);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }
}
