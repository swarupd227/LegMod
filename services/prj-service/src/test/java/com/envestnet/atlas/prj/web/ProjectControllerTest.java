package com.envestnet.atlas.prj.web;

import com.envestnet.atlas.prj.audit.AuditLogger;
import com.envestnet.atlas.prj.auth.GatewayIdentity;
import com.envestnet.atlas.prj.auth.WorkspaceAccess;
import com.envestnet.atlas.prj.domain.Gate;
import com.envestnet.atlas.prj.domain.Project;
import com.envestnet.atlas.prj.domain.WorkspaceMember;
import com.envestnet.atlas.prj.repo.GateRepository;
import com.envestnet.atlas.prj.repo.ProjectRepository;
import com.envestnet.atlas.prj.repo.WorkspaceMemberRepository;
import com.envestnet.atlas.prj.repo.WorkspaceRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pure-unit coverage of ProjectController's stage/gate state machine. Repos
 * and downstream RestTemplate calls are mocked; no Spring context is started.
 */
class ProjectControllerTest {

    private WorkspaceRepository workspaces;
    private ProjectRepository projects;
    private GateRepository gates;
    private WorkspaceMemberRepository memberRepo;
    private WorkspaceAccess access;
    private AuditLogger audit;
    private RestTemplate http;
    private io.temporal.client.WorkflowClient workflowClient;
    private ProjectController controller;

    @BeforeEach
    void setUp() {
        workspaces = mock(WorkspaceRepository.class);
        projects   = mock(ProjectRepository.class);
        gates      = mock(GateRepository.class);
        memberRepo = mock(WorkspaceMemberRepository.class);
        // Default: any (workspace, user) pair is OWNER. The controller
        // tests focus on the stage/gate state machine; cross-tenant
        // denial has its own focused suite in
        // ProjectControllerAuthorizationTest. Tests that need to flip
        // this stub deny it on the per-test basis.
        when(memberRepo.findByWorkspaceAndUser(any(UUID.class), any(String.class)))
                .thenAnswer(inv -> Optional.of(new WorkspaceMember(
                        UUID.randomUUID(),
                        inv.getArgument(0),
                        inv.getArgument(1),
                        "OWNER",
                        OffsetDateTime.now(),
                        "test")));
        access = new WorkspaceAccess(memberRepo);
        // Mock the audit logger — these tests assert on the stage / gate
        // state machine, not the audit table. AuditLoggerTest covers
        // the writer in isolation against H2.
        audit = mock(AuditLogger.class);
        // SimpleMeterRegistry: in-memory MeterRegistry that doesn't try
        // to expose anything. The metric increments are real but
        // unobserved unless a test explicitly inspects the registry.
        var metrics = new com.envestnet.atlas.prj.metrics.AtlasMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        // The Temporal client is mocked — tests don't submit workflows
        // (the orchestration paths have their own TestWorkflowEnvironment
        // coverage in ArchaeologyWorkflowTest). Tests that DO exercise
        // runArchaeology stub the client's behavior locally.
        workflowClient = mock(io.temporal.client.WorkflowClient.class);
        http       = mock(RestTemplate.class);
        controller = new ProjectController(
                workspaces, memberRepo, projects, gates, access, audit, http, metrics, workflowClient,
                "http://arch", "http://cap", "http://recon", "http://gen",
                "http://diff", "http://reports", "http://prov", "http://uplift");
        // Most tests don't care about identity, but findReadable/findWritable
        // require a user email in the request context. Bind a default
        // engineer identity so the existing stage-flow tests still pass;
        // tests that need a specific identity (e.g. the transitionedBy
        // tests) call bindRequestWithIdentity explicitly to overwrite.
        bindRequestWithIdentity("alice@envestnet.local", "Alice", "ENGINEER");
    }

    @AfterEach
    void tearDown() {
        // Clean up any RequestContextHolder state left by tests that exercise
        // GatewayIdentity. Otherwise it bleeds into the next test.
        RequestContextHolder.resetRequestAttributes();
        // Workspace context stashed by findReadable/findWritable lives in
        // the SLF4J MDC; the RequestContextFilter clears it in production
        // but the controller-only test harness doesn't run the filter, so
        // wipe it here to keep tests isolated.
        org.slf4j.MDC.clear();
    }

    /**
     * Bind a synthetic HTTP request carrying the gateway identity headers
     * to the current thread so {@link GatewayIdentity} can read them.
     */
    private void bindRequestWithIdentity(String email, String name, String roles) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (email != null) req.addHeader(GatewayIdentity.HDR_EMAIL, email);
        if (name  != null) req.addHeader(GatewayIdentity.HDR_NAME,  name);
        if (roles != null) req.addHeader(GatewayIdentity.HDR_ROLES, roles);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    @Test
    void createProjectAppliesDefaultsAndSeedsAllSixGatesAsPending() {
        // Repo echoes back saved Project with a generated id.
        when(projects.save(any())).thenAnswer(inv -> {
            Project p = inv.getArgument(0);
            return new Project(UUID.randomUUID(), p.workspaceId(), p.name(), p.description(),
                    p.mode(), p.sourceFramework(), p.targetFramework(), p.targetJavaVersion(),
                    p.vendorPartner(), p.owner(), p.riskTier(), p.currentStage(),
                    p.sourcePath(), p.metrics(), p.createdAt(), p.updatedAt());
        });
        when(gates.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var req = new ProjectController.CreateProjectRequest(
                null, "Demo", "desc",
                null,                       // mode → defaults to SOAP
                "Axis 1.4", "Spring 3",
                null,                       // java → defaults to "21"
                "Broadridge",
                null,                       // owner → default
                null,                       // riskTier → MEDIUM
                "/legacy");

        ResponseEntity<Map<String, Object>> resp = controller.createProject(req);
        Map<String, Object> body = resp.getBody();

        assertThat(body).isNotNull();
        assertThat(body.get("mode")).isEqualTo("SOAP");
        assertThat(body.get("owner")).isEqualTo("dev@envestnet.local");
        assertThat(body.get("riskTier")).isEqualTo("MEDIUM");
        assertThat(body.get("currentStage")).isEqualTo("A");

        // Project saved exactly once.
        ArgumentCaptor<Project> projCap = ArgumentCaptor.forClass(Project.class);
        verify(projects).save(projCap.capture());
        Project saved = projCap.getValue();
        assertThat(saved.targetJavaVersion()).isEqualTo("21");
        assertThat(saved.workspaceId())
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));

        // 6 gates created — A,B,C,D,E,F all pending.
        ArgumentCaptor<Gate> gateCap = ArgumentCaptor.forClass(Gate.class);
        verify(gates, times(6)).save(gateCap.capture());
        List<Gate> created = gateCap.getAllValues();
        assertThat(created).extracting(Gate::label)
                .containsExactly("A", "B", "C", "D", "E", "F");
        assertThat(created).allSatisfy(g -> assertThat(g.state()).isEqualTo("pending"));
    }

    @Test
    void runArchaeologyCallsArchServiceAndAdvancesAToB() {
        // Demo-fast path: runArchaeology calls arch-service directly
        // and advances the project from Stage A to B inline. The
        // Phase 2I Temporal wrapper is reserved for a follow-up; see
        // the comment in ProjectController.runArchaeology.
        UUID pid = UUID.randomUUID();
        Project p = projectAt(pid, "A", "/legacy/src");
        when(projects.findById(pid)).thenReturn(Optional.of(p));
        when(gates.findByProject(pid)).thenReturn(allPendingGates(pid));
        when(projects.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gates.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(http.postForObject(contains("/internal/archaeology/run"), any(), eq(Map.class)))
                .thenReturn(Map.of("runId", "run-" + pid, "status", "completed"));

        ResponseEntity<?> resp = controller.runArchaeology(pid);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsKeys("runId", "status");

        // Gate A passed, Gate B in_progress — same state machine as
        // the other sync stages.
        ArgumentCaptor<Gate> gateCap = ArgumentCaptor.forClass(Gate.class);
        verify(gates, times(2)).save(gateCap.capture());
        Map<String, String> stateByLabel = new HashMap<>();
        gateCap.getAllValues().forEach(g -> stateByLabel.put(g.label(), g.state()));
        assertThat(stateByLabel).containsEntry("A", "passed");
        assertThat(stateByLabel).containsEntry("B", "in_progress");
    }

    @Test
    void runArchaeologyRejectsProjectWithNoSource() {
        UUID pid = UUID.randomUUID();
        Project p = projectAt(pid, "A", null);
        when(projects.findById(pid)).thenReturn(Optional.of(p));

        ResponseEntity<?> resp = controller.runArchaeology(pid);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verify(projects, never()).save(any());
        verify(gates, never()).save(any());
        // No downstream call attempted either.
        verify(http, never()).postForObject(any(String.class), any(), eq(Map.class));
    }

    @Test
    void runReconciliationAdvancesBToPassedAndCToInProgress() {
        UUID pid = UUID.randomUUID();
        Project p = projectAt(pid, "B", "/src");
        when(projects.findById(pid)).thenReturn(Optional.of(p));
        when(gates.findByProject(pid)).thenReturn(allPendingGates(pid));
        when(http.postForObject(contains("/internal/recon/projects/"), any(), eq(Map.class)))
                .thenReturn(Map.of("ok", true));
        when(projects.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gates.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.runReconciliation(pid);

        ArgumentCaptor<Gate> gateCap = ArgumentCaptor.forClass(Gate.class);
        verify(gates, times(2)).save(gateCap.capture());
        Map<String, String> stateByLabel = new HashMap<>();
        gateCap.getAllValues().forEach(g -> stateByLabel.put(g.label(), g.state()));
        assertThat(stateByLabel).containsEntry("B", "passed");
        assertThat(stateByLabel).containsEntry("C", "in_progress");
    }

    @Test
    void runGenerationPassesGateDOnlyWhenErrorCountIsZero() {
        UUID pid = UUID.randomUUID();
        Project p = projectAt(pid, "C", "/src");
        when(projects.findById(pid)).thenReturn(Optional.of(p));
        when(gates.findByProject(pid)).thenReturn(allPendingGates(pid));
        when(projects.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gates.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // status=completed AND errorCount=0  → gate D should be marked passed.
        when(http.postForObject(contains("/internal/generation/projects/"), any(), eq(Map.class)))
                .thenReturn(Map.of("status", "completed", "errorCount", 0));

        controller.runGeneration(pid, null);

        Map<String, String> stateByLabel = stateByLabelFromAllSaves();
        assertThat(stateByLabel).containsEntry("D", "passed");
    }

    @Test
    void runGenerationKeepsGateDInProgressWhenThereAreErrors() {
        UUID pid = UUID.randomUUID();
        Project p = projectAt(pid, "C", "/src");
        when(projects.findById(pid)).thenReturn(Optional.of(p));
        when(gates.findByProject(pid)).thenReturn(allPendingGates(pid));
        when(projects.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gates.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(http.postForObject(contains("/internal/generation/projects/"), any(), eq(Map.class)))
                .thenReturn(Map.of("status", "completed", "errorCount", 3));

        controller.runGeneration(pid, null);

        // D is set to in_progress (advanceStage), never to passed.
        ArgumentCaptor<Gate> gateCap = ArgumentCaptor.forClass(Gate.class);
        verify(gates, atLeastOnce()).save(gateCap.capture());
        boolean dPassed = gateCap.getAllValues().stream()
                .anyMatch(g -> "D".equals(g.label()) && "passed".equals(g.state()));
        assertThat(dPassed).isFalse();
        boolean dInProgress = gateCap.getAllValues().stream()
                .anyMatch(g -> "D".equals(g.label()) && "in_progress".equals(g.state()));
        assertThat(dInProgress).isTrue();
    }

    @Test
    void runDiffPassesGateEOnlyWhenRedCountIsZero() {
        UUID pid = UUID.randomUUID();
        Project p = projectAt(pid, "D", "/src");
        when(projects.findById(pid)).thenReturn(Optional.of(p));
        when(gates.findByProject(pid)).thenReturn(allPendingGates(pid));
        when(projects.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gates.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(http.postForObject(contains("/internal/diff/projects/"), any(), eq(Map.class)))
                .thenReturn(Map.of("status", "completed", "redCount", 0));

        controller.runDiff(pid, null);

        Map<String, String> stateByLabel = stateByLabelFromAllSaves();
        assertThat(stateByLabel).containsEntry("E", "passed");
    }

    @Test
    void runDiffDoesNotPassGateEWhenRedDivergencesRemain() {
        UUID pid = UUID.randomUUID();
        Project p = projectAt(pid, "D", "/src");
        when(projects.findById(pid)).thenReturn(Optional.of(p));
        when(gates.findByProject(pid)).thenReturn(allPendingGates(pid));
        when(projects.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gates.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(http.postForObject(contains("/internal/diff/projects/"), any(), eq(Map.class)))
                .thenReturn(Map.of("status", "completed", "redCount", 5));

        controller.runDiff(pid, null);

        ArgumentCaptor<Gate> gateCap = ArgumentCaptor.forClass(Gate.class);
        verify(gates, atLeastOnce()).save(gateCap.capture());
        boolean ePassed = gateCap.getAllValues().stream()
                .anyMatch(g -> "E".equals(g.label()) && "passed".equals(g.state()));
        assertThat(ePassed).isFalse();
    }

    @Test
    void buildBundlePassesGateFWhenReportsCompletes() {
        UUID pid = UUID.randomUUID();
        Project p = projectAt(pid, "E", "/src");
        when(projects.findById(pid)).thenReturn(Optional.of(p));
        when(gates.findByProject(pid)).thenReturn(allPendingGates(pid));
        when(projects.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gates.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(http.postForObject(contains("/internal/reports/projects/"), any(), eq(Map.class)))
                .thenReturn(Map.of("status", "completed"));

        controller.buildBundle(pid);

        Map<String, String> stateByLabel = stateByLabelFromAllSaves();
        assertThat(stateByLabel).containsEntry("F", "passed");
    }

    @Test
    void notFoundProjectsAreReportedAs404Across404PoneCalls() {
        UUID pid = UUID.randomUUID();
        when(projects.findById(pid)).thenReturn(Optional.empty());

        assertThat(controller.runArchaeology(pid).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.runReconciliation(pid).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.runGeneration(pid, null).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.runDiff(pid, null).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.buildBundle(pid).getStatusCode().value()).isEqualTo(404);

        verify(projects, never()).save(any());
        verify(gates, never()).save(any());
        verifyNoInteractions(http);
    }

    @Test
    void attachSourceUpdatesPathAndPreservesProjectMetadata() {
        UUID pid = UUID.randomUUID();
        Project original = projectAt(pid, "A", null);
        when(projects.findById(pid)).thenReturn(Optional.of(original));
        when(projects.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.attachSource(pid, new ProjectController.AttachSourceRequest("/new/src"));

        ArgumentCaptor<Project> projCap = ArgumentCaptor.forClass(Project.class);
        verify(projects).save(projCap.capture());
        Project saved = projCap.getValue();
        assertThat(saved.sourcePath()).isEqualTo("/new/src");
        assertThat(saved.id()).isEqualTo(pid);
        assertThat(saved.name()).isEqualTo(original.name()); // metadata preserved
        assertThat(saved.currentStage()).isEqualTo("A");     // stage unchanged
    }

    @Test
    void runInventoryAdvancesAToBForUpliftTrack() {
        UUID pid = UUID.randomUUID();
        Project p = projectAt(pid, "A", "/upliftSrc", "UPLIFT");
        when(projects.findById(pid)).thenReturn(Optional.of(p));
        when(gates.findByProject(pid)).thenReturn(allPendingGates(pid));
        when(projects.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gates.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(http.postForObject(contains("/internal/uplift/projects/"), any(), eq(Map.class)))
                .thenReturn(Map.of("ok", true));

        controller.runInventory(pid);

        ArgumentCaptor<Gate> gateCap = ArgumentCaptor.forClass(Gate.class);
        verify(gates, times(2)).save(gateCap.capture());
        Map<String, String> stateByLabel = new HashMap<>();
        gateCap.getAllValues().forEach(g -> stateByLabel.put(g.label(), g.state()));
        assertThat(stateByLabel).containsEntry("A", "passed");
        assertThat(stateByLabel).containsEntry("B", "in_progress");
    }

    @Test
    void gateTransitionsRecordTheGatewayAssertedUserAsTransitionedBy() {
        // Drive a sync stage transition (UPLIFT A → B via seedRecipes;
        // archaeology runs are now async via Temporal, see
        // ArchaeologyWorkflowTest). The contract being asserted —
        // "every persisted Gate carries the gateway-asserted actor" —
        // is identical, just through a different endpoint.
        bindRequestWithIdentity("bob@envestnet.local", "Bob", "ENGINEER,TECH_LEAD");

        UUID pid = UUID.randomUUID();
        Project p = projectAt(pid, "A", "/legacy/src", "UPLIFT");
        when(projects.findById(pid)).thenReturn(Optional.of(p));
        when(gates.findByProject(pid)).thenReturn(allPendingGates(pid));
        when(projects.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gates.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(http.postForObject(contains("/internal/uplift/projects/"), any(), eq(Map.class)))
                .thenReturn(Map.of("ok", true));

        controller.seedRecipes(pid);

        ArgumentCaptor<Gate> gateCap = ArgumentCaptor.forClass(Gate.class);
        verify(gates, times(2)).save(gateCap.capture());
        assertThat(gateCap.getAllValues())
                .allSatisfy(g -> assertThat(g.transitionedBy()).isEqualTo("bob@envestnet.local"));
    }

    @Test
    void controllerDeniesRequestsThatArriveWithoutGatewayAssertedIdentity() {
        // Phase 2K hardening: every project-touching endpoint requires
        // a gateway-asserted identity to resolve workspace membership.
        // A request that somehow lands in the controller without one
        // (misconfigured gateway, direct internal call) returns 404 —
        // the existence-hiding shape of a workspace-access deny.
        //
        // Pre-2K, the controller would have fallen back to the "system"
        // actor and proceeded; that path is no longer reachable from a
        // controller route. (The "system" fallback still applies inside
        // background work that calls advanceStage/advanceGate directly
        // — see ArchaeologyWorkflowTest.)
        RequestContextHolder.resetRequestAttributes();
        UUID pid = UUID.randomUUID();
        Project p = projectAt(pid, "A", "/legacy/src", "UPLIFT");
        when(projects.findById(pid)).thenReturn(Optional.of(p));

        ResponseEntity<?> resp = controller.seedRecipes(pid);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        // Crucially: no gate write happened, no downstream call happened.
        verify(gates, never()).save(any());
        verifyNoInteractions(http);
    }

    /* ---------------- helpers ---------------- */

    private Project projectAt(UUID pid, String stage, String sourcePath) {
        return projectAt(pid, stage, sourcePath, "SOAP");
    }

    private Project projectAt(UUID pid, String stage, String sourcePath, String mode) {
        OffsetDateTime now = OffsetDateTime.now();
        return new Project(
                pid,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "Demo Project",
                "desc",
                mode,
                "Axis 1.4",
                "Spring Boot 3.3",
                "21",
                "Broadridge",
                "alice@envestnet.local",
                "MEDIUM",
                stage,
                sourcePath,
                "{}",
                now,
                now);
    }

    private List<Gate> allPendingGates(UUID pid) {
        List<Gate> out = new ArrayList<>();
        for (String label : List.of("A", "B", "C", "D", "E", "F")) {
            out.add(new Gate(UUID.randomUUID(), pid, label, "pending", null, null));
        }
        return out;
    }

    private Map<String, String> stateByLabelFromAllSaves() {
        ArgumentCaptor<Gate> gateCap = ArgumentCaptor.forClass(Gate.class);
        verify(gates, atLeastOnce()).save(gateCap.capture());
        Map<String, String> out = new HashMap<>();
        // Last-writer-wins so we capture the terminal state per label.
        gateCap.getAllValues().forEach(g -> out.put(g.label(), g.state()));
        return out;
    }

    @SuppressWarnings("unused")
    private static HttpEntity<?> anyEntity() { return any(HttpEntity.class); }
}
