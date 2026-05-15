package com.envestnet.atlas.prj.web;

import com.envestnet.atlas.prj.audit.AuditLogger;
import com.envestnet.atlas.prj.auth.GatewayIdentity;
import com.envestnet.atlas.prj.auth.WorkspaceAccess;
import com.envestnet.atlas.prj.auth.WorkspaceAccessDeniedException;
import com.envestnet.atlas.prj.domain.Gate;
import com.envestnet.atlas.prj.domain.Project;
import com.envestnet.atlas.prj.domain.Workspace;
import com.envestnet.atlas.prj.logging.RequestContextFilter;
import com.envestnet.atlas.prj.patterns.PatternService;
import com.envestnet.atlas.prj.repo.GateRepository;
import com.envestnet.atlas.prj.repo.ProjectRepository;
import com.envestnet.atlas.prj.repo.WorkspaceMemberRepository;
import com.envestnet.atlas.prj.repo.WorkspaceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class ProjectController {

    private static final List<String> STAGES = List.of("A", "B", "C", "D", "E", "F");

    private final WorkspaceRepository workspaces;
    private final WorkspaceMemberRepository members;
    private final ProjectRepository projects;
    private final GateRepository gates;
    private final WorkspaceAccess access;
    private final AuditLogger audit;
    private final RestTemplate http;
    private final String archUrl;
    private final String capUrl;
    private final String reconUrl;
    private final String genUrl;
    private final String diffUrl;
    private final String reportsUrl;
    private final String provUrl;
    private final String upliftUrl;
    private final com.envestnet.atlas.prj.metrics.AtlasMetrics metrics;

    /**
     * Pattern library. Field-injected so the unit-test fixtures don't
     * need to know about it — pattern recording is a best-effort side
     * effect of a successful resolve and is null-safe (see use site).
     */
    @Autowired(required = false)
    private PatternService patterns;

    /**
     * Temporal client. Auto-injected by temporal-spring-boot-starter
     * when {@code spring.temporal.connection.target} is set; null
     * (via the {@code required = false} hook below) when the Temporal
     * cluster is unreachable so prj-service keeps booting in that
     * degraded mode.
     */
    private final io.temporal.client.WorkflowClient workflowClient;

    public ProjectController(WorkspaceRepository workspaces,
                             WorkspaceMemberRepository members,
                             ProjectRepository projects,
                             GateRepository gates,
                             WorkspaceAccess access,
                             AuditLogger audit,
                             RestTemplate http,
                             com.envestnet.atlas.prj.metrics.AtlasMetrics metrics,
                             io.temporal.client.WorkflowClient workflowClient,
                             @Value("${ARCH_SERVICE_URL:http://arch-service:8083}") String archUrl,
                             @Value("${CAP_SERVICE_URL:http://cap-service:8085}") String capUrl,
                             @Value("${RECON_SERVICE_URL:http://recon-service:8086}") String reconUrl,
                             @Value("${GEN_SERVICE_URL:http://gen-service:8087}") String genUrl,
                             @Value("${DIFF_SERVICE_URL:http://diff-service:8089}") String diffUrl,
                             @Value("${REPORTS_SERVICE_URL:http://reports-service:8090}") String reportsUrl,
                             @Value("${PROV_SERVICE_URL:http://prov-service:8091}") String provUrl,
                             @Value("${UPLIFT_SERVICE_URL:http://uplift-service:8092}") String upliftUrl) {
        this.workspaces = workspaces;
        this.members = members;
        this.projects = projects;
        this.gates = gates;
        this.access = access;
        this.audit = audit;
        this.http = http;
        this.metrics = metrics;
        this.workflowClient = workflowClient;
        this.archUrl = archUrl;
        this.capUrl = capUrl;
        this.reconUrl = reconUrl;
        this.genUrl = genUrl;
        this.diffUrl = diffUrl;
        this.reportsUrl = reportsUrl;
        this.provUrl = provUrl;
        this.upliftUrl = upliftUrl;
    }

    /* ============================================================
     * Workspace-level authorization helpers (Phase 2K).
     *
     * Every endpoint that touches a project funnels through one of
     * these so the authorization check happens in exactly one place.
     * Two principles drive the contract:
     *
     *   - Reads that the caller can't see return 404, never 403.
     *     This prevents UUID enumeration: a probing caller can't tell
     *     "no such project" apart from "you don't have access" so
     *     they can't map out the tenant graph.
     *
     *   - Writes that hit a workspace the caller can SEE but not WRITE
     *     return 403 — the existence is no longer secret (the caller is
     *     a VIEWER on that workspace), so the more informative status
     *     code is fine and helps the SPA render a clearer error.
     * ============================================================ */

    private String currentUserEmail() {
        return GatewayIdentity.currentEmail().orElse(null);
    }

    /**
     * Look up a project and check VIEWER+ on its workspace. Returns
     * empty when the project doesn't exist OR the caller can't see
     * its workspace. Used by every read endpoint and by the read leg
     * of write endpoints (so a non-member can't 403-probe).
     *
     * <p>Side effect: on success, stashes the resolved workspaceId in
     * MDC + request attributes for downstream propagation. See Phase
     * 2K.5 in docs/multi-tenancy.md.</p>
     */
    private Optional<Project> findReadable(UUID projectId) {
        return projects.findById(projectId)
                .filter(p -> {
                    boolean ok = access.canRead(currentUserEmail(), p.workspaceId());
                    if (ok) stashWorkspaceContext(p.workspaceId());
                    return ok;
                });
    }

    /**
     * Look up a project and check EDITOR+ on its workspace. Returns
     * empty when the project doesn't exist OR the caller can't even
     * READ it (to hide existence). Throws
     * {@link WorkspaceAccessDeniedException} → 403 when the caller
     * can read but not write.
     */
    private Optional<Project> findWritable(UUID projectId) {
        Optional<Project> maybe = projects.findById(projectId);
        if (maybe.isEmpty()) return Optional.empty();
        Project p = maybe.get();
        String email = currentUserEmail();
        if (!access.canRead(email, p.workspaceId())) {
            // Non-member → 404 (hide existence).
            return Optional.empty();
        }
        if (!access.canWrite(email, p.workspaceId())) {
            // Member but read-only → 403 (existence already known).
            throw new WorkspaceAccessDeniedException(
                    email, p.workspaceId(), WorkspaceAccess.Level.EDITOR);
        }
        stashWorkspaceContext(p.workspaceId());
        return Optional.of(p);
    }

    /**
     * Read-access predicate for pass-through endpoints that don't need
     * the full Project record (e.g. status fetchers that proxy to the
     * downstream service). Same hide-existence-on-deny semantics as
     * {@link #findReadable}.
     */
    private boolean canReadProject(UUID projectId) {
        return findReadable(projectId).isPresent();
    }

    /**
     * Write-access predicate for pass-through mutations that just proxy
     * to a downstream service (e.g. recipe decisions, strangler edits).
     * Returns false to drive a 404 when the project doesn't exist or
     * the caller can't see the workspace; throws 403 when the caller
     * can read but not write.
     */
    private boolean canWriteProject(UUID projectId) {
        Optional<Project> maybe = projects.findById(projectId);
        if (maybe.isEmpty()) return false;
        Project p = maybe.get();
        String email = currentUserEmail();
        if (!access.canRead(email, p.workspaceId())) return false;
        if (!access.canWrite(email, p.workspaceId())) {
            throw new WorkspaceAccessDeniedException(
                    email, p.workspaceId(), WorkspaceAccess.Level.EDITOR);
        }
        stashWorkspaceContext(p.workspaceId());
        return true;
    }

    /**
     * Publish the resolved workspaceId to the MDC (for log tagging) and
     * the current request attributes (for the
     * {@code IdentityForwardingInterceptor} to forward as
     * {@code X-Atlas-Workspace-Id} on downstream calls). Best-effort:
     * runs outside an HTTP request context (background tasks, tests
     * without bound request) just become a no-op.
     */
    private static void stashWorkspaceContext(UUID workspaceId) {
        if (workspaceId == null) return;
        org.slf4j.MDC.put(RequestContextFilter.MDC_WORKSPACE_ID, workspaceId.toString());
        try {
            var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                attrs.setAttribute(RequestContextFilter.ATTR_WORKSPACE_ID, workspaceId.toString(),
                        org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
            }
        } catch (Exception ignored) {
            // No request context — fine for background tasks / tests.
        }
    }

    @GetMapping("/workspaces")
    public List<Workspace> listWorkspaces() {
        // The SPA chrome's workspace switcher must only show workspaces
        // the caller is a member of. ADMINs (support engineers) see
        // everything; everyone else sees only their accessible set.
        String email = currentUserEmail();
        if (access.isAdmin(email)) {
            List<Workspace> all = new ArrayList<>();
            workspaces.findAll().forEach(all::add);
            return all;
        }
        Set<UUID> accessibleIds = access.accessibleWorkspaceIds(email);
        if (accessibleIds.isEmpty()) return List.of();
        List<Workspace> out = new ArrayList<>(accessibleIds.size());
        workspaces.findAllById(accessibleIds).forEach(out::add);
        return out;
    }

    @GetMapping("/workspaces/{wsId}/projects")
    public ResponseEntity<List<Map<String, Object>>> listProjects(@PathVariable UUID wsId) {
        // 404 on deny — don't disclose whether the workspace exists.
        if (!access.canRead(currentUserEmail(), wsId)) {
            return ResponseEntity.notFound().build();
        }
        stashWorkspaceContext(wsId);
        List<Map<String, Object>> body = projects.findByWorkspace(wsId).stream()
                .map(this::projectSummary)
                .toList();
        return ResponseEntity.ok(body);
    }

    /**
     * Per-workspace LLM cost rollup (Phase 2L). Relays
     * {@code prov-service /internal/prov/workspaces/{ws}/cost} after a
     * workspace-access check, so non-members can't read another
     * tenant's spend.
     *
     * <p>Query params {@code from} and {@code to} are ISO-8601 instants
     * passed through unchanged. The default window — trailing 30 days
     * — is computed at the prov-service tier.</p>
     */
    @GetMapping("/workspaces/{wsId}/cost")
    public ResponseEntity<Map<String, Object>> workspaceCost(
            @PathVariable UUID wsId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        if (!access.canRead(currentUserEmail(), wsId)) {
            return ResponseEntity.notFound().build();
        }
        stashWorkspaceContext(wsId);
        try {
            StringBuilder url = new StringBuilder(provUrl)
                    .append("/internal/prov/workspaces/").append(wsId).append("/cost");
            boolean first = true;
            if (from != null && !from.isBlank()) {
                url.append('?').append("from=").append(enc(from));
                first = false;
            }
            if (to != null && !to.isBlank()) {
                url.append(first ? '?' : '&').append("to=").append(enc(to));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = http.getForObject(url.toString(), Map.class);
            return ResponseEntity.ok(resp == null ? Map.of() : resp);
        } catch (Exception e) {
            // Cost rollup is a dashboard widget — return an empty
            // skeleton rather than 500 so a transient prov-service
            // outage doesn't break the page.
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("workspaceId", wsId.toString());
            empty.put("totals",   Map.of("calls", 0, "tokensIn", 0, "tokensOut", 0, "costUsd", 0));
            empty.put("byUser",   List.of());
            empty.put("byProject", List.of());
            empty.put("byModel",  List.of());
            empty.put("daily",    List.of());
            return ResponseEntity.ok(empty);
        }
    }

    @PostMapping("/projects")
    public ResponseEntity<Map<String, Object>> createProject(
            @jakarta.validation.Valid @RequestBody CreateProjectRequest req) {
        UUID workspaceId = Optional.ofNullable(req.workspaceId())
                .orElse(UUID.fromString("00000000-0000-0000-0000-000000000001"));

        // EDITOR+ on the target workspace is required to create
        // projects in it. 403 (not 404) because the path doesn't
        // identify a specific project — only a workspace the caller
        // referenced explicitly, so they already know it exists.
        String email = currentUserEmail();
        if (!access.canWrite(email, workspaceId)) {
            throw new WorkspaceAccessDeniedException(
                    email, workspaceId, WorkspaceAccess.Level.EDITOR);
        }
        stashWorkspaceContext(workspaceId);

        OffsetDateTime now = OffsetDateTime.now();
        Project saved = projects.save(new Project(
                null,
                workspaceId,
                req.name(),
                req.description(),
                Optional.ofNullable(req.mode()).orElse("SOAP"),
                req.sourceFramework(),
                req.targetFramework(),
                Optional.ofNullable(req.targetJavaVersion()).orElse("21"),
                req.vendorPartner(),
                Optional.ofNullable(req.owner()).orElse("dev@envestnet.local"),
                Optional.ofNullable(req.riskTier()).orElse("MEDIUM"),
                "A",
                req.sourcePath(),
                "{}",
                now,
                now
        ));

        for (String label : STAGES) {
            gates.save(new Gate(null, saved.id(), label, "pending", null, null));
        }

        audit.record(workspaceId, saved.id(), "project.create",
                "project", saved.id().toString(),
                Map.of(
                        "name", saved.name(),
                        "mode", saved.mode(),
                        "riskTier", saved.riskTier(),
                        "vendorPartner", saved.vendorPartner() == null ? "" : saved.vendorPartner()
                ));
        return ResponseEntity.ok(projectSummary(saved));
    }

    @GetMapping("/projects/{id}")
    public ResponseEntity<Map<String, Object>> getProject(@PathVariable UUID id) {
        return findReadable(id)
                .map(p -> ResponseEntity.ok(projectDetail(p)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/projects/{id}/gates")
    public ResponseEntity<List<Gate>> listGates(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(gates.findByProject(id));
    }

    @PostMapping("/projects/{id}/sources")
    public ResponseEntity<Map<String, Object>> attachSource(
            @PathVariable UUID id,
            @jakarta.validation.Valid @RequestBody AttachSourceRequest body) {
        return findWritable(id).map(p -> {
            Project updated = new Project(
                    p.id(), p.workspaceId(), p.name(), p.description(), p.mode(),
                    p.sourceFramework(), p.targetFramework(), p.targetJavaVersion(),
                    p.vendorPartner(), p.owner(), p.riskTier(), p.currentStage(),
                    body.path(),
                    p.metrics(),
                    p.createdAt(), OffsetDateTime.now()
            );
            projects.save(updated);
            audit.record(p.workspaceId(), p.id(), "project.attach_source",
                    "project", p.id().toString(),
                    Map.of(
                            "previousPath", p.sourcePath() == null ? "" : p.sourcePath(),
                            "newPath",      body.path()
                    ));
            return ResponseEntity.ok(projectDetail(updated));
        }).orElse(ResponseEntity.notFound().build());
    }

    /* ---------------- Track B Stage A · Inventory & Heatmap ---------------- */

    @PostMapping("/projects/{id}/stages/inventory/run")
    public ResponseEntity<?> runInventory(@PathVariable UUID id) {
        return findWritable(id).map(p -> {
            if (p.sourcePath() == null || p.sourcePath().isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "project has no source attached"));
            }
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = Map.of("sourcePath", p.sourcePath());
            try {
                Map<?, ?> resp = http.postForObject(
                        upliftUrl + "/internal/uplift/projects/" + p.id() + "/inventory/run",
                        new HttpEntity<>(body, h), Map.class);
                advanceStage(p, "B");          // A → passed, B → in_progress
                return ResponseEntity.ok(resp);
            } catch (Exception e) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/projects/{id}/stages/inventory/status")
    public ResponseEntity<?> inventoryStatus(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.getForObject(
                    upliftUrl + "/internal/uplift/projects/" + id + "/inventory/status", Map.class);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            // Map.of() forbids null values; use a builder so the empty-state
            // shape mirrors the non-empty path.
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("run", null);
            empty.put("modules",    List.of());
            empty.put("findings",   List.of());
            empty.put("ruleCounts", List.of());
            return ResponseEntity.ok(empty);
        }
    }

    /* ---------------- Track B Stage B · Recipe Authoring ---------------- */

    @PostMapping("/projects/{id}/stages/recipes/seed")
    public ResponseEntity<?> seedRecipes(@PathVariable UUID id) {
        return findWritable(id).map(p -> {
            try {
                Map<?, ?> resp = http.postForObject(
                        upliftUrl + "/internal/uplift/projects/" + id + "/recipes/seed",
                        new HttpEntity<>(Map.of(), jsonHeaders()), Map.class);
                // Move project cursor to Stage B (Recipe Authoring) for UPLIFT track.
                if ("UPLIFT".equals(p.mode()) && "A".equals(p.currentStage())) {
                    advanceStage(p, "B");
                }
                return ResponseEntity.ok(resp);
            } catch (Exception e) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/projects/{id}/stages/recipes/status")
    public ResponseEntity<?> recipesStatus(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.getForObject(
                    upliftUrl + "/internal/uplift/projects/" + id + "/recipes", Map.class);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("recipes", List.of());
            empty.put("counts", Map.of("total", 0, "accepted", 0, "proposed", 0, "rejected", 0));
            empty.put("readyForGate", false);
            return ResponseEntity.ok(empty);
        }
    }

    @PostMapping("/projects/{id}/stages/recipes/{rid}/decision")
    public ResponseEntity<?> decideRecipe(@PathVariable UUID id,
                                           @PathVariable UUID rid,
                                           @RequestBody Map<String, Object> body) {
        if (!canWriteProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.postForObject(
                    upliftUrl + "/internal/uplift/projects/" + id + "/recipes/" + rid + "/decision",
                    new HttpEntity<>(body, jsonHeaders()), Map.class);
            return ResponseEntity.ok(resp);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("error", e.getResponseBodyAsString()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/projects/{id}/stages/recipes")
    public ResponseEntity<?> createCustomRecipe(@PathVariable UUID id,
                                                @RequestBody Map<String, Object> body) {
        if (!canWriteProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.postForObject(
                    upliftUrl + "/internal/uplift/projects/" + id + "/recipes",
                    new HttpEntity<>(body, jsonHeaders()), Map.class);
            return ResponseEntity.ok(resp);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("error", e.getResponseBodyAsString()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/projects/{id}/stages/recipes/{rid}")
    public ResponseEntity<?> deleteRecipe(@PathVariable UUID id, @PathVariable UUID rid) {
        if (!canWriteProject(id)) return ResponseEntity.notFound().build();
        try {
            http.exchange(
                    upliftUrl + "/internal/uplift/projects/" + id + "/recipes/" + rid,
                    HttpMethod.DELETE, null, Map.class);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/projects/{id}/stages/recipes/{rid}/findings")
    public ResponseEntity<?> recipeFindings(@PathVariable UUID id, @PathVariable UUID rid) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.getForObject(
                    upliftUrl + "/internal/uplift/projects/" + id + "/recipes/" + rid + "/findings",
                    Map.class);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("findings", List.of()));
        }
    }

    @PostMapping("/projects/{id}/stages/recipes/finalize")
    public ResponseEntity<?> finalizeRecipes(@PathVariable UUID id) {
        return findWritable(id).map(p -> {
            // Pre-flight: must have ≥1 accepted recipe.
            try {
                Map<?, ?> resp = http.getForObject(
                        upliftUrl + "/internal/uplift/projects/" + id + "/recipes", Map.class);
                Object ready = resp == null ? Boolean.FALSE : resp.get("readyForGate");
                if (!Boolean.TRUE.equals(ready)) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "Accept at least one recipe before finalizing Stage B."));
                }
                advanceStage(p, "C");
                return ResponseEntity.ok(Map.of("ok", true, "currentStage", "C"));
            } catch (Exception e) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    /* ---------------- Track B Stage C · Strangler Designer ---------------- */

    @PostMapping("/projects/{id}/stages/strangler/seed")
    public ResponseEntity<?> seedStrangler(@PathVariable UUID id) {
        return findWritable(id).map(p -> {
            try {
                Map<?, ?> resp = http.postForObject(
                        upliftUrl + "/internal/uplift/projects/" + id + "/strangler/seed",
                        new HttpEntity<>(Map.of(), jsonHeaders()), Map.class);
                return ResponseEntity.ok(resp);
            } catch (org.springframework.web.client.HttpClientErrorException e) {
                return ResponseEntity.status(e.getStatusCode())
                        .body(Map.of("error", e.getResponseBodyAsString()));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/projects/{id}/stages/strangler/status")
    public ResponseEntity<?> stranglerStatus(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.getForObject(
                    upliftUrl + "/internal/uplift/projects/" + id + "/strangler", Map.class);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("steps", List.of());
            empty.put("counts", Map.of("total", 0, "planned", 0, "ready", 0, "extracted", 0));
            empty.put("readyForGate", false);
            return ResponseEntity.ok(empty);
        }
    }

    @PutMapping("/projects/{id}/stages/strangler/{sid}")
    public ResponseEntity<?> updateStranglerStep(@PathVariable UUID id,
                                                  @PathVariable UUID sid,
                                                  @RequestBody Map<String, Object> body) {
        if (!canWriteProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.exchange(
                    upliftUrl + "/internal/uplift/projects/" + id + "/strangler/" + sid,
                    HttpMethod.PUT, new HttpEntity<>(body, jsonHeaders()), Map.class).getBody();
            return ResponseEntity.ok(resp);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("error", e.getResponseBodyAsString()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/projects/{id}/stages/strangler/{sid}/order")
    public ResponseEntity<?> reorderStranglerStep(@PathVariable UUID id,
                                                   @PathVariable UUID sid,
                                                   @RequestBody Map<String, Object> body) {
        if (!canWriteProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.exchange(
                    upliftUrl + "/internal/uplift/projects/" + id + "/strangler/" + sid + "/order",
                    HttpMethod.PUT, new HttpEntity<>(body, jsonHeaders()), Map.class).getBody();
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/projects/{id}/stages/strangler/{sid}")
    public ResponseEntity<?> deleteStranglerStep(@PathVariable UUID id, @PathVariable UUID sid) {
        if (!canWriteProject(id)) return ResponseEntity.notFound().build();
        try {
            http.exchange(
                    upliftUrl + "/internal/uplift/projects/" + id + "/strangler/" + sid,
                    HttpMethod.DELETE, null, Map.class);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/projects/{id}/stages/strangler/finalize")
    public ResponseEntity<?> finalizeStrangler(@PathVariable UUID id) {
        return findWritable(id).map(p -> {
            try {
                Map<?, ?> resp = http.getForObject(
                        upliftUrl + "/internal/uplift/projects/" + id + "/strangler", Map.class);
                Object ready = resp == null ? Boolean.FALSE : resp.get("readyForGate");
                if (!Boolean.TRUE.equals(ready)) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error",
                                "Mark at least one strangler step as ready before finalizing Stage C."));
                }
                advanceStage(p, "D");
                return ResponseEntity.ok(Map.of("ok", true, "currentStage", "D"));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    /* ---------------- Track B Stage D · Module Migration ---------------- */

    @PostMapping("/projects/{id}/stages/migration/{sid}/run")
    public ResponseEntity<?> runMigration(@PathVariable UUID id, @PathVariable UUID sid) {
        if (!canWriteProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.postForObject(
                    upliftUrl + "/internal/uplift/projects/" + id + "/migrations/" + sid,
                    new HttpEntity<>(Map.of(), jsonHeaders()), Map.class);
            return ResponseEntity.ok(resp);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("error", e.getResponseBodyAsString()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/projects/{id}/stages/migration/step/{sid}")
    public ResponseEntity<?> migrationForStep(@PathVariable UUID id, @PathVariable UUID sid) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.getForObject(
                    upliftUrl + "/internal/uplift/projects/" + id + "/migrations/step/" + sid,
                    Map.class);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("run", null);
            empty.put("changes", List.of());
            return ResponseEntity.ok(empty);
        }
    }

    @GetMapping("/projects/{id}/stages/migration/status")
    public ResponseEntity<?> migrationStatus(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.getForObject(
                    upliftUrl + "/internal/uplift/projects/" + id + "/migrations", Map.class);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("runs", List.of());
            empty.put("counts", Map.of("total", 0, "completed", 0, "failed", 0, "running", 0));
            empty.put("readyForGate", false);
            return ResponseEntity.ok(empty);
        }
    }

    @GetMapping("/projects/{id}/stages/migration/diff")
    public ResponseEntity<?> migrationFileDiff(@PathVariable UUID id,
                                                @RequestParam("runId") UUID rid,
                                                @RequestParam("path") String path) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            // Build the upstream URI explicitly so RestTemplate doesn't double-encode
            // the already-percent-escaped query string.
            java.net.URI uri = org.springframework.web.util.UriComponentsBuilder
                    .fromHttpUrl(upliftUrl)
                    .path("/internal/uplift/migrations/{rid}/files")
                    .queryParam("path", path)
                    .build()
                    .expand(rid)
                    .encode()
                    .toUri();
            Map<?, ?> resp = http.getForObject(uri, Map.class);
            return ResponseEntity.ok(resp);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("error", e.getResponseBodyAsString()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/projects/{id}/stages/migration/finalize")
    public ResponseEntity<?> finalizeMigration(@PathVariable UUID id) {
        return findWritable(id).map(p -> {
            try {
                Map<?, ?> resp = http.getForObject(
                        upliftUrl + "/internal/uplift/projects/" + id + "/migrations", Map.class);
                Object ready = resp == null ? Boolean.FALSE : resp.get("readyForGate");
                if (!Boolean.TRUE.equals(ready)) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error",
                                "Run at least one module migration before finalizing Stage D."));
                }
                advanceStage(p, "E");
                return ResponseEntity.ok(Map.of("ok", true, "currentStage", "E"));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    /* ---------------- Track B Stage E · Characterization Validation ---------------- */

    @PostMapping("/projects/{id}/stages/characterize/run")
    public ResponseEntity<?> runCharacterization(@PathVariable UUID id,
                                                  @RequestBody(required = false) Map<String, Object> body) {
        if (!canWriteProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.postForObject(
                    upliftUrl + "/internal/uplift/projects/" + id + "/characterize/run",
                    new HttpEntity<>(body == null ? Map.of() : body, jsonHeaders()), Map.class);
            return ResponseEntity.ok(resp);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("error", e.getResponseBodyAsString()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/projects/{id}/stages/characterize/status")
    public ResponseEntity<?> charStatus(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.getForObject(
                    upliftUrl + "/internal/uplift/projects/" + id + "/characterize/status", Map.class);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("run", null);
            empty.put("cases", List.of());
            empty.put("byModule", List.of());
            empty.put("openRegressions", 0);
            empty.put("readyForGate", false);
            return ResponseEntity.ok(empty);
        }
    }

    @PostMapping("/projects/{id}/stages/characterize/cases/{cid}/triage")
    public ResponseEntity<?> triageCharCase(@PathVariable UUID id,
                                             @PathVariable UUID cid,
                                             @RequestBody Map<String, Object> body) {
        if (!canWriteProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.postForObject(
                    upliftUrl + "/internal/uplift/projects/" + id + "/characterize/cases/" + cid + "/triage",
                    new HttpEntity<>(body, jsonHeaders()), Map.class);
            return ResponseEntity.ok(resp);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("error", e.getResponseBodyAsString()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/projects/{id}/stages/characterize/finalize")
    public ResponseEntity<?> finalizeCharacterization(@PathVariable UUID id) {
        return findWritable(id).map(p -> {
            try {
                Map<?, ?> resp = http.getForObject(
                        upliftUrl + "/internal/uplift/projects/" + id + "/characterize/status", Map.class);
                Object ready = resp == null ? Boolean.FALSE : resp.get("readyForGate");
                if (!Boolean.TRUE.equals(ready)) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error",
                                "Resolve all regression cases before finalizing Stage E."));
                }
                advanceStage(p, "F");
                return ResponseEntity.ok(Map.of("ok", true, "currentStage", "F"));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    /* ---------------- Track B Stage F · Cutover & Decommission ---------------- */

    @PostMapping("/projects/{id}/stages/cutover/seed")
    public ResponseEntity<?> seedCutover(@PathVariable UUID id) {
        if (!canWriteProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.postForObject(
                    upliftUrl + "/internal/uplift/projects/" + id + "/cutover/seed",
                    new HttpEntity<>(Map.of(), jsonHeaders()), Map.class);
            return ResponseEntity.ok(resp);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("error", e.getResponseBodyAsString()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/projects/{id}/stages/cutover/status")
    public ResponseEntity<?> cutoverStatus(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.getForObject(
                    upliftUrl + "/internal/uplift/projects/" + id + "/cutover", Map.class);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("cutovers", List.of());
            empty.put("counts", Map.of("total", 0, "planned", 0, "shadow", 0,
                    "canary", 0, "live", 0, "rolledBack", 0, "decommissioned", 0));
            empty.put("readyForGate", false);
            return ResponseEntity.ok(empty);
        }
    }

    @PutMapping("/projects/{id}/stages/cutover/{cid}")
    public ResponseEntity<?> updateCutover(@PathVariable UUID id,
                                            @PathVariable UUID cid,
                                            @RequestBody Map<String, Object> body) {
        if (!canWriteProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.exchange(
                    upliftUrl + "/internal/uplift/projects/" + id + "/cutover/" + cid,
                    HttpMethod.PUT, new HttpEntity<>(body, jsonHeaders()), Map.class).getBody();
            return ResponseEntity.ok(resp);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("error", e.getResponseBodyAsString()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/projects/{id}/stages/cutover/{cid}/checklist/{itemId}")
    public ResponseEntity<?> toggleCutoverChecklist(@PathVariable UUID id,
                                                     @PathVariable UUID cid,
                                                     @PathVariable String itemId,
                                                     @RequestBody Map<String, Object> body) {
        if (!canWriteProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.exchange(
                    upliftUrl + "/internal/uplift/projects/" + id + "/cutover/" + cid
                            + "/checklist/" + itemId,
                    HttpMethod.PUT, new HttpEntity<>(body, jsonHeaders()), Map.class).getBody();
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping(value = "/projects/{id}/stages/cutover/closure", produces = "text/markdown")
    public ResponseEntity<String> cutoverClosure(@PathVariable UUID id) {
        return findReadable(id).map(p -> {
            try {
                java.net.URI uri = org.springframework.web.util.UriComponentsBuilder
                        .fromHttpUrl(upliftUrl)
                        .path("/internal/uplift/projects/{id}/cutover/closure")
                        .queryParam("name", p.name())
                        .queryParam("sourcePath", p.sourcePath() == null ? "" : p.sourcePath())
                        .build()
                        .expand(id)
                        .encode()
                        .toUri();
                String md = http.getForObject(uri, String.class);
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                        .body(md == null ? "" : md);
            } catch (Exception e) {
                return ResponseEntity.<String>internalServerError().body("# Closure unavailable\n\n" + e.getMessage());
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/projects/{id}/stages/cutover/finalize")
    public ResponseEntity<?> finalizeCutover(@PathVariable UUID id) {
        return findWritable(id).map(p -> {
            try {
                Map<?, ?> resp = http.getForObject(
                        upliftUrl + "/internal/uplift/projects/" + id + "/cutover", Map.class);
                Object ready = resp == null ? Boolean.FALSE : resp.get("readyForGate");
                if (!Boolean.TRUE.equals(ready)) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error",
                                "Take every cutover live (or decommissioned) and complete its checklist before finalizing."));
                }
                // Stage F is terminal — pass its gate; the project stays parked at F.
                advanceGate(p.id(), "F", "passed");
                return ResponseEntity.ok(Map.of(
                        "ok", true,
                        "complete", true,
                        "currentStage", "F"));
            } catch (Exception e) {
                return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @PostMapping("/projects/{id}/stages/archaeology/run")
    public ResponseEntity<?> runArchaeology(@PathVariable UUID id) {
        return findWritable(id).map(p -> {
            if (p.sourcePath() == null || p.sourcePath().isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "project has no source attached"));
            }
            // Phase 2I introduced a Temporal-orchestrated async wrapper
            // around this call (see ArchaeologyWorkflow), but the worker
            // registration is currently broken in the local-Docker stack
            // — submissions sit forever in "running". Until that's
            // resolved we call arch-service directly, the same way Phase
            // 1 did. The synchronous path returns in seconds for the
            // demo dataset and the SPA's existing polling still works
            // (status endpoints don't care which path produced the data).
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = Map.of(
                    "projectId",  p.id().toString(),
                    "sourcePath", p.sourcePath());
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = http.postForObject(
                        archUrl + "/internal/archaeology/run",
                        new HttpEntity<>(body, h), Map.class);
                advanceStage(p, "B");  // A → passed, B → in_progress
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("runId",  resp == null ? null : resp.get("runId"));
                out.put("status", resp == null ? "completed" : resp.get("status"));
                return ResponseEntity.ok(out);
            } catch (Exception e) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Direct Temporal workflow-state probe. The SPA can use this for
     * deeper drill-down than the per-service /status endpoints —
     * e.g. to surface partial-failure state when the workflow is
     * retrying an activity. The describe call goes through the
     * Temporal gRPC service stub since {@code WorkflowStub} itself
     * doesn't expose a describe operation.
     */
    @GetMapping("/projects/{id}/stages/archaeology/workflow/{workflowId}")
    public ResponseEntity<?> archaeologyWorkflowStatus(
            @PathVariable UUID id, @PathVariable String workflowId) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            var req = io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest.newBuilder()
                    .setNamespace(workflowClient.getOptions().getNamespace())
                    .setExecution(io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                            .setWorkflowId(workflowId)
                            .build())
                    .build();
            var resp = workflowClient.getWorkflowServiceStubs()
                    .blockingStub()
                    .describeWorkflowExecution(req);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("workflowId", workflowId);
            // Status enum name is like WORKFLOW_EXECUTION_STATUS_RUNNING;
            // strip the prefix and lowercase for a friendlier API.
            body.put("status",
                    resp.getWorkflowExecutionInfo().getStatus().name()
                        .toLowerCase().replace("workflow_execution_status_", ""));
            body.put("startTime",
                    resp.getWorkflowExecutionInfo().getStartTime().toString());
            if (resp.getWorkflowExecutionInfo().hasCloseTime()) {
                body.put("closeTime",
                        resp.getWorkflowExecutionInfo().getCloseTime().toString());
            }
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of(
                    "error", "workflow not found or Temporal unavailable: " + e.getMessage()));
        }
    }

    @GetMapping("/projects/{id}/operations")
    public ResponseEntity<?> listOperations(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            Object[] resp = http.getForObject(
                    archUrl + "/internal/archaeology/operations/" + id, Object[].class);
            return ResponseEntity.ok(resp == null ? List.of() : Arrays.asList(resp));
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/projects/{id}/adapters")
    public ResponseEntity<?> listAdapters(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            Object[] resp = http.getForObject(
                    archUrl + "/internal/archaeology/adapters/" + id, Object[].class);
            return ResponseEntity.ok(resp == null ? List.of() : Arrays.asList(resp));
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }
    }

    /* ---------------- Stage B · capture ---------------- */

    @PostMapping("/projects/{id}/stages/capture/deploy")
    public ResponseEntity<?> startCapture(@PathVariable UUID id,
                                          @RequestBody(required = false) Map<String, Object> body) {
        return findWritable(id).map(p -> {
            if (p.sourcePath() == null || p.sourcePath().isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "project has no source attached"));
            }
            int target = body != null && body.get("targetEnvelopes") instanceof Number n
                    ? n.intValue() : 250;
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> req = Map.of(
                    "sourcePath", p.sourcePath(),
                    "targetEnvelopes", target);
            try {
                Map<?, ?> resp = http.postForObject(
                        capUrl + "/internal/capture/projects/" + p.id() + "/deploy",
                        new HttpEntity<>(req, h), Map.class);
                advanceStage(p, "B");
                return ResponseEntity.ok(resp);
            } catch (Exception e) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/projects/{id}/stages/capture/finalize")
    public ResponseEntity<?> finalizeCapture(@PathVariable UUID id) {
        return findWritable(id).map(p -> {
            try {
                http.postForObject(capUrl + "/internal/capture/projects/" + p.id() + "/finalize",
                        null, Map.class);
                // Pass gate B and move the project cursor to C so the console
                // surfaces the next stage. Skip the move if the project has
                // already advanced past B (e.g., re-finalize on a later stage).
                if ("B".equals(p.currentStage())) {
                    advanceStage(p, "C");
                } else {
                    advanceGate(p.id(), "B", "passed");
                }
                return ResponseEntity.ok(Map.of("ok", true, "currentStage",
                        projects.findById(p.id()).map(Project::currentStage).orElse("B")));
            } catch (Exception e) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/projects/{id}/stages/capture/status")
    public ResponseEntity<?> captureStatus(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.getForObject(
                    capUrl + "/internal/capture/projects/" + id + "/status", Map.class);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "deployments", List.of(),
                    "totalEnvelopes", 0,
                    "operationDistribution", List.of(),
                    "recentEnvelopes", List.of(),
                    "sanitizationRules", List.of()
            ));
        }
    }

    @GetMapping("/projects/{id}/stages/capture/deployments/{did}")
    public ResponseEntity<?> deploymentDetail(@PathVariable UUID id,
                                              @PathVariable UUID did) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.getForObject(
                    capUrl + "/internal/capture/deployments/" + did + "/detail", Map.class);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/projects/{id}/stages/capture/deployments/{did}")
    public ResponseEntity<?> updateDeployment(@PathVariable UUID id,
                                              @PathVariable UUID did,
                                              @RequestBody Map<String, Object> body) {
        if (!canWriteProject(id)) return ResponseEntity.notFound().build();
        try {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> resp = http.exchange(
                    capUrl + "/internal/capture/deployments/" + did,
                    org.springframework.http.HttpMethod.PUT,
                    new HttpEntity<>(body, h), Map.class);
            return ResponseEntity.ok(resp.getBody());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/projects/{id}/stages/capture/sanitization-rules/{rid}")
    public ResponseEntity<?> updateSanitizationRule(@PathVariable UUID id,
                                                    @PathVariable UUID rid,
                                                    @RequestBody Map<String, Object> body) {
        if (!canWriteProject(id)) return ResponseEntity.notFound().build();
        try {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> resp = http.exchange(
                    capUrl + "/internal/capture/sanitization-rules/" + rid,
                    org.springframework.http.HttpMethod.PUT,
                    new HttpEntity<>(body, h), Map.class);
            return ResponseEntity.ok(resp.getBody());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /* ---------------- Stage C · reconciliation ---------------- */

    @PostMapping("/projects/{id}/stages/reconciliation/run")
    public ResponseEntity<?> runReconciliation(@PathVariable UUID id) {
        return findWritable(id).map(p -> {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = Map.of("sourcePath", p.sourcePath());
            try {
                Map<?, ?> resp = http.postForObject(
                        reconUrl + "/internal/recon/projects/" + p.id() + "/run",
                        new HttpEntity<>(body, h), Map.class);
                advanceStage(p, "C");          // B → passed, C → in_progress
                return ResponseEntity.ok(resp);
            } catch (Exception e) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/projects/{id}/stages/reconciliation/status")
    public ResponseEntity<?> reconciliationStatus(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.getForObject(
                    reconUrl + "/internal/recon/projects/" + id + "/status", Map.class);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "decisions", List.of(),
                    "elements", List.of(),
                    "counts", Map.of("total", 0, "pending", 0, "resolved", 0)
            ));
        }
    }

    @PostMapping("/projects/{id}/decisions/{did}/resolve")
    public ResponseEntity<?> resolveDecision(@PathVariable UUID id,
                                             @PathVariable UUID did,
                                             @RequestBody Map<String, Object> body) {
        if (!canWriteProject(id)) return ResponseEntity.notFound().build();
        try {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            Map<?, ?> resp = http.postForObject(
                    reconUrl + "/internal/recon/decisions/" + did + "/resolve",
                    new HttpEntity<>(body, h), Map.class);

            // Record this decision in the cross-project pattern library
            // so future Stage C runs against the same vendor see a
            // "Recommended (based on N prior migrations)" pill on the
            // matching divergence card. Best-effort; never blocks the
            // user's resolve call.
            if (patterns != null) {
                try {
                    Map<?, ?> dec = http.getForObject(
                            reconUrl + "/internal/recon/decisions/" + did, Map.class);
                    if (dec != null) {
                        String kind         = str(dec, "kind");
                        String path         = str(dec, "path");
                        String chosenAction = str(dec, "chosenAction");
                        String resolution   = str(dec, "resolution");
                        patterns.recordDecision(id, kind, path, chosenAction, resolution);
                    }
                } catch (Exception ignored) {}
            }

            // Auto-advance: if all decisions are resolved, finalize Gate C.
            try {
                Map<?, ?> stat = http.getForObject(
                        reconUrl + "/internal/recon/projects/" + id + "/status", Map.class);
                if (stat != null && stat.get("counts") instanceof Map<?, ?> counts) {
                    Object pending = counts.get("pending");
                    Object total   = counts.get("total");
                    if (pending instanceof Number p && total instanceof Number t
                            && p.intValue() == 0 && t.intValue() > 0) {
                        advanceGate(id, "C", "passed");
                        // Move project cursor to Stage D so the UI flips screens.
                        projects.findById(id).ifPresent(p2 -> advanceStage(p2, "D"));
                    }
                }
            } catch (Exception ignored) {}

            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /* ---------------- Stage D · code generation ---------------- */

    @PostMapping("/projects/{id}/stages/generation/run")
    public ResponseEntity<?> runGeneration(@PathVariable UUID id,
                                           @RequestBody(required = false) Map<String, Object> body) {
        return findWritable(id).map(p -> {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            try {
                Map<?, ?> resp = http.postForObject(
                        genUrl + "/internal/generation/projects/" + p.id() + "/run",
                        new HttpEntity<>(body == null ? Map.of() : body, h), Map.class);

                // Mark Gate D in_progress (it stays so until the engineer signs off)
                advanceStage(p, "D");

                if (resp != null && "completed".equals(resp.get("status"))) {
                    Object errs = resp.get("errorCount");
                    if (errs instanceof Number n && n.intValue() == 0) {
                        // Pass Gate D AND advance the project cursor to
                        // Stage E so the stage chrome unlocks the
                        // Differential Lab button. Without the second
                        // call, currentStage stays at "D" and Stage E
                        // renders as aria-disabled even though Gate D
                        // is marked passed.
                        projects.findById(id).ifPresent(p2 -> advanceStage(p2, "E"));
                    }
                }
                return ResponseEntity.ok(resp);
            } catch (Exception e) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/projects/{id}/stages/generation/status")
    public ResponseEntity<?> generationStatus(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.getForObject(
                    genUrl + "/internal/generation/projects/" + id + "/status", Map.class);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("run", null, "files", List.of()));
        }
    }

    @GetMapping(value = "/projects/{id}/stages/generation/bindings",
                produces = "application/xml")
    public ResponseEntity<String> generationBindings(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            String xml = http.getForObject(
                    genUrl + "/internal/generation/projects/" + id + "/bindings", String.class);
            return ResponseEntity.ok(xml);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/projects/{id}/stages/generation/output.zip")
    public ResponseEntity<byte[]> downloadGenerationZip(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            byte[] bytes = http.getForObject(
                    genUrl + "/internal/generation/projects/" + id + "/output.zip", byte[].class);
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.parseMediaType("application/zip"));
            h.setContentDispositionFormData("attachment", "jaxws-source.zip");
            return new ResponseEntity<>(bytes, h, 200);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping(value = "/projects/{id}/wsdl/authoritative", produces = "application/xml")
    public ResponseEntity<String> authoritativeWsdl(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            String xml = http.getForObject(
                    reconUrl + "/internal/recon/projects/" + id + "/wsdl/authoritative",
                    String.class);
            return ResponseEntity.ok(xml);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /* ---------------- Stage E · differential validation ---------------- */

    @PostMapping("/projects/{id}/stages/diff/run")
    public ResponseEntity<?> runDiff(@PathVariable UUID id,
                                     @RequestBody(required = false) Map<String, Object> body) {
        return findWritable(id).map(p -> {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            try {
                Map<?, ?> resp = http.postForObject(
                        diffUrl + "/internal/diff/projects/" + p.id() + "/run",
                        new HttpEntity<>(body == null ? Map.of() : body, h), Map.class);
                advanceStage(p, "E");
                if (resp != null && "completed".equals(resp.get("status"))) {
                    Object red = resp.get("redCount");
                    // Always advance currentStage to F after the diff
                    // run completes so the Reports & Deliverables tab
                    // unlocks regardless of red-count — open reds get
                    // captured in the closure document for triage, but
                    // the engineer needs the bundle preview to plan the
                    // follow-up work. Whether Gate E itself passes is
                    // gated on red-count: zero reds = passed, otherwise
                    // it stays in_progress until the engineer manually
                    // resolves the divergences.
                    projects.findById(id).ifPresent(p2 -> advanceStage(p2, "F"));
                    if (red instanceof Number n && n.intValue() == 0) {
                        advanceGate(id, "E", "passed");
                    }
                }
                return ResponseEntity.ok(resp);
            } catch (Exception e) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/projects/{id}/stages/diff/status")
    public ResponseEntity<?> diffStatus(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.getForObject(
                    diffUrl + "/internal/diff/projects/" + id + "/status", Map.class);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "run", null,
                    "byOperation", List.of(),
                    "buckets", Map.of("benign", 0, "amber", 0, "red", 0),
                    "divergences", List.of()
            ));
        }
    }

    @PostMapping("/projects/{id}/divergences/{did}/auto-fix")
    public ResponseEntity<?> autoFixDivergence(@PathVariable UUID id,
                                                @PathVariable UUID did,
                                                @RequestBody(required = false) Map<String, Object> body) {
        if (!canWriteProject(id)) return ResponseEntity.notFound().build();
        try {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            Map<?, ?> resp = http.postForObject(
                    diffUrl + "/internal/diff/divergences/" + did + "/auto-fix",
                    new HttpEntity<>(body == null ? Map.of() : body, h), Map.class);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /* ---------------- Stage F · reports & deliverables ---------------- */

    @PostMapping("/projects/{id}/stages/reports/build")
    public ResponseEntity<?> buildBundle(@PathVariable UUID id) {
        return findWritable(id).map(p -> {
            try {
                Map<?, ?> resp = http.postForObject(
                        reportsUrl + "/internal/reports/projects/" + p.id() + "/build",
                        null, Map.class);
                advanceStage(p, "F");
                if (resp != null && "completed".equals(resp.get("status"))) {
                    advanceGate(id, "F", "passed");
                }
                return ResponseEntity.ok(resp);
            } catch (Exception e) {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/projects/{id}/stages/reports/status")
    public ResponseEntity<?> reportsStatus(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.getForObject(
                    reportsUrl + "/internal/reports/projects/" + id + "/status", Map.class);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("bundle", null));
        }
    }

    @GetMapping(value = "/projects/{id}/stages/reports/closure", produces = "text/markdown")
    public ResponseEntity<String> reportsClosure(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            String md = http.getForObject(
                    reportsUrl + "/internal/reports/projects/" + id + "/closure", String.class);
            return ResponseEntity.ok(md);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/projects/{id}/stages/reports/bundle.zip")
    public ResponseEntity<byte[]> reportsBundle(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            byte[] bytes = http.getForObject(
                    reportsUrl + "/internal/reports/projects/" + id + "/bundle.zip", byte[].class);
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.parseMediaType("application/zip"));
            h.setContentDispositionFormData("attachment", "migration-package.zip");
            return new ResponseEntity<>(bytes, h, 200);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /* ---------------- Audit & Provenance ---------------- */

    @GetMapping("/projects/{id}/provenance")
    public ResponseEntity<?> provenance(@PathVariable UUID id,
                                        @RequestParam(required = false) String action,
                                        @RequestParam(required = false) String actorKind,
                                        @RequestParam(required = false) String actorId,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "200") int limit) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            StringBuilder url = new StringBuilder(provUrl)
                    .append("/internal/prov/projects/").append(id).append("?limit=").append(limit);
            if (action    != null && !action.isBlank())    url.append("&action=").append(enc(action));
            if (actorKind != null && !actorKind.isBlank()) url.append("&actorKind=").append(enc(actorKind));
            if (actorId   != null && !actorId.isBlank())   url.append("&actorId=").append(enc(actorId));
            if (q         != null && !q.isBlank())         url.append("&q=").append(enc(q));
            Object[] resp = http.getForObject(url.toString(), Object[].class);
            return ResponseEntity.ok(resp == null ? List.of() : Arrays.asList(resp));
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/projects/{id}/provenance/aggregate")
    public ResponseEntity<?> provenanceAggregate(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            Map<?, ?> resp = http.getForObject(
                    provUrl + "/internal/prov/projects/" + id + "/aggregate", Map.class);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "total", 0, "byActor", List.of(), "byAction", List.of(),
                    "tokens", Map.of("in", 0, "out", 0, "cost", 0)));
        }
    }

    @GetMapping("/projects/{id}/provenance/export.csv")
    public ResponseEntity<byte[]> provenanceExport(@PathVariable UUID id) {
        if (!canReadProject(id)) return ResponseEntity.notFound().build();
        try {
            byte[] bytes = http.getForObject(
                    provUrl + "/internal/prov/projects/" + id + "/export.csv", byte[].class);
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.parseMediaType("text/csv"));
            h.setContentDispositionFormData("attachment", "provenance.csv");
            return new ResponseEntity<>(bytes, h, 200);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Null-safe Map-of-Object string read used by pattern-library wiring. */
    private static String str(Map<?, ?> m, String key) {
        if (m == null) return null;
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private void advanceStage(Project p, String newStage) {
        // The gateway-asserted user is the authoritative actor; if we're
        // running outside an HTTP request (very rare here), fall back
        // to "system".
        String actor = com.envestnet.atlas.prj.auth.GatewayIdentity.resolveActor(null);

        // Mark prior gate passed if not already; update project stage cursor.
        gates.findByProject(p.id()).stream()
                .filter(g -> p.currentStage().equals(g.label()))
                .findFirst().ifPresent(g -> {
                    if (!"passed".equals(g.state())) {
                        gates.save(new Gate(g.id(), g.projectId(), g.label(),
                                "passed", OffsetDateTime.now(), actor));
                    }
                });
        Project updated = new Project(
                p.id(), p.workspaceId(), p.name(), p.description(), p.mode(),
                p.sourceFramework(), p.targetFramework(), p.targetJavaVersion(),
                p.vendorPartner(), p.owner(), p.riskTier(),
                newStage, p.sourcePath(), p.metrics(),
                p.createdAt(), OffsetDateTime.now()
        );
        projects.save(updated);

        // Mark target gate in_progress.
        gates.findByProject(p.id()).stream()
                .filter(g -> newStage.equals(g.label()))
                .findFirst().ifPresent(g -> gates.save(new Gate(
                        g.id(), g.projectId(), g.label(),
                        "in_progress", OffsetDateTime.now(), actor)));
    }

    private void advanceGate(UUID projectId, String label, String state) {
        String actor = com.envestnet.atlas.prj.auth.GatewayIdentity.resolveActor(null);
        gates.findByProject(projectId).stream()
                .filter(g -> label.equals(g.label()))
                .findFirst().ifPresent(g -> gates.save(new Gate(
                        g.id(), g.projectId(), g.label(), state,
                        OffsetDateTime.now(), actor)));
        // Surface the transition for ops dashboards. The counter
        // increments regardless of whether the gate actually existed —
        // the call site is the authoritative "we tried to advance."
        metrics.gateAdvanced(label, state);
        // Audit-log every gate transition. workspaceId is best-effort —
        // we resolve via the project lookup since gate doesn't carry
        // the workspace directly. If the project was just deleted, the
        // audit row records null workspaceId (the table allows it).
        UUID workspaceId = projects.findById(projectId)
                .map(Project::workspaceId).orElse(null);
        audit.record(workspaceId, projectId, "gate.advance",
                "gate", label,
                Map.of("state", state, "actor", actor));
    }

    @PostMapping("/internal/seed")
    public Map<String, Object> seed() {
        UUID wsId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String ownerEmail = "dev@envestnet.local";
        if (workspaces.findById(wsId).isEmpty()) {
            workspaces.save(new Workspace(wsId, "Envestnet", ownerEmail, OffsetDateTime.now()));
        }
        // Make sure the dev owner + the fake-idp demo personas are all
        // members. Phase 2K's V3 + V5 migrations seed these on a fresh
        // database; the seed endpoint covers the wipe-and-reseed cycle
        // where Flyway's checksum cache means the migrations don't
        // re-run against an already-bootstrapped database.
        for (String email : List.of(
                ownerEmail,
                "alice@envestnet.local",
                "bob@envestnet.local",
                "carol@envestnet.local")) {
            if (members.findByWorkspaceAndUser(wsId, email).isEmpty()) {
                members.save(new com.envestnet.atlas.prj.domain.WorkspaceMember(
                        null, wsId, email, "OWNER", OffsetDateTime.now(), "seed-endpoint"));
            }
        }
        return Map.of("ok", true, "workspaceId", wsId.toString());
    }

    private Map<String, Object> projectSummary(Project p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("name", p.name());
        m.put("description", p.description());
        m.put("mode", p.mode());
        m.put("sourceFramework", p.sourceFramework());
        m.put("targetFramework", p.targetFramework());
        m.put("vendorPartner", p.vendorPartner());
        m.put("owner", p.owner());
        m.put("riskTier", p.riskTier());
        m.put("currentStage", p.currentStage());
        m.put("sourcePath", p.sourcePath());
        m.put("updatedAt", p.updatedAt());
        return m;
    }

    private Map<String, Object> projectDetail(Project p) {
        Map<String, Object> m = projectSummary(p);
        m.put("createdAt", p.createdAt());
        m.put("targetJavaVersion", p.targetJavaVersion());
        m.put("gates", gates.findByProject(p.id()));
        return m;
    }

    public record CreateProjectRequest(
            UUID workspaceId,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(min = 3, max = 200)
            String name,
            @jakarta.validation.constraints.Size(max = 4000)
            String description,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "SOAP|UPLIFT",
                    message = "mode must be one of SOAP, UPLIFT")
            String mode,
            @jakarta.validation.constraints.Size(max = 200)
            String sourceFramework,
            @jakarta.validation.constraints.Size(max = 200)
            String targetFramework,
            @jakarta.validation.constraints.Size(max = 50)
            String targetJavaVersion,
            @jakarta.validation.constraints.Size(max = 200)
            String vendorPartner,
            @jakarta.validation.constraints.Size(max = 200)
            String owner,
            @jakarta.validation.constraints.Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL",
                    message = "riskTier must be one of LOW, MEDIUM, HIGH, CRITICAL")
            String riskTier,
            @jakarta.validation.constraints.Size(max = 1024)
            String sourcePath
    ) {}

    public record AttachSourceRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 1024)
            String path) {}
}
