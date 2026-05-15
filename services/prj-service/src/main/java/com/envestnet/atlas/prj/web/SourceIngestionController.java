package com.envestnet.atlas.prj.web;

import com.envestnet.atlas.prj.auth.GatewayIdentity;
import com.envestnet.atlas.prj.auth.WorkspaceAccess;
import com.envestnet.atlas.prj.auth.WorkspaceAccessDeniedException;
import com.envestnet.atlas.prj.domain.Project;
import com.envestnet.atlas.prj.ingest.IngestionService;
import com.envestnet.atlas.prj.repo.ProjectRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Source-ingestion endpoints — the customer-facing alternative to
 * dropping a Linux container path into a textbox. Two modes:
 *
 * <ul>
 *   <li>{@code POST /api/v1/projects/{id}/sources/github} — clone a
 *       public repo (optionally sparse-checkout a subpath) into a
 *       per-project workspace and set the project's {@code sourcePath}
 *       to the resolved tree</li>
 *   <li>{@code POST /api/v1/projects/{id}/sources/upload} — extract a
 *       .zip the SPA uploaded via multipart into the same workspace</li>
 *   <li>{@code GET  /api/v1/projects/{id}/sources/status} — poll the
 *       latest ingestion status for the project</li>
 * </ul>
 *
 * <p>All three endpoints flow through Phase 2K's {@code WorkspaceAccess}
 * guard — the project must exist AND the caller must be at least an
 * EDITOR of its workspace. 404 hides existence for non-members; 403
 * fires for read-only members.</p>
 */
@RestController
@RequestMapping("/api/v1/projects")
public class SourceIngestionController {

    private final IngestionService ingest;
    private final ProjectRepository projects;
    private final WorkspaceAccess access;

    public SourceIngestionController(IngestionService ingest,
                                       ProjectRepository projects,
                                       WorkspaceAccess access) {
        this.ingest = ingest;
        this.projects = projects;
        this.access = access;
    }

    @PostMapping("/{id}/sources/github")
    public ResponseEntity<Map<String, Object>> ingestGithub(
            @PathVariable UUID id,
            @RequestBody GithubIngestRequest req) {
        Project p = requireWritable(id);
        if (p == null) return ResponseEntity.notFound().build();

        IngestionService.Status s = ingest.ingestFromGithub(
                id, req.url(), req.branch(), req.subpath());
        if (s.state() == IngestionService.State.READY) {
            persistSourcePath(p, s.sourcePath());
        }
        return ResponseEntity.ok(toMap(s));
    }

    @PostMapping(value = "/{id}/sources/upload",
                 consumes = "multipart/form-data")
    public ResponseEntity<Map<String, Object>> ingestUpload(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file) {
        Project p = requireWritable(id);
        if (p == null) return ResponseEntity.notFound().build();

        IngestionService.Status s = ingest.ingestFromUpload(id, file);
        if (s.state() == IngestionService.State.READY) {
            persistSourcePath(p, s.sourcePath());
        }
        return ResponseEntity.ok(toMap(s));
    }

    @GetMapping("/{id}/sources/status")
    public ResponseEntity<Map<String, Object>> sourceStatus(@PathVariable UUID id) {
        // Read-access predicate: the caller must at least be a VIEWER.
        // We don't enforce write here — peeking at ingest status is fine.
        Optional<Project> maybe = projects.findById(id);
        if (maybe.isEmpty()) return ResponseEntity.notFound().build();
        String email = GatewayIdentity.currentEmail().orElse(null);
        if (!access.canRead(email, maybe.get().workspaceId())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toMap(ingest.status(id)));
    }

    /* ---------------- helpers ---------------- */

    /**
     * Find a project that the current caller can write to. Returns
     * null on 404 (project missing OR not visible); throws on 403
     * (visible but read-only).
     */
    private Project requireWritable(UUID id) {
        Optional<Project> maybe = projects.findById(id);
        if (maybe.isEmpty()) return null;
        Project p = maybe.get();
        String email = GatewayIdentity.currentEmail().orElse(null);
        if (!access.canRead(email, p.workspaceId())) return null;
        if (!access.canWrite(email, p.workspaceId())) {
            throw new WorkspaceAccessDeniedException(
                    email, p.workspaceId(), WorkspaceAccess.Level.EDITOR);
        }
        return p;
    }

    private void persistSourcePath(Project p, String sourcePath) {
        Project updated = new Project(
                p.id(), p.workspaceId(), p.name(), p.description(), p.mode(),
                p.sourceFramework(), p.targetFramework(), p.targetJavaVersion(),
                p.vendorPartner(), p.owner(), p.riskTier(), p.currentStage(),
                sourcePath, p.metrics(), p.createdAt(), OffsetDateTime.now());
        projects.save(updated);
    }

    private static Map<String, Object> toMap(IngestionService.Status s) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("projectId",   s.projectId());
        m.put("state",       s.state().name().toLowerCase());
        m.put("mode",        s.mode());
        m.put("message",     s.message());
        m.put("sourcePath",  s.sourcePath());
        m.put("bytes",       s.bytes());
        m.put("files",       s.files());
        m.put("startedAt",   s.startedAt());
        m.put("finishedAt",  s.finishedAt());
        return m;
    }

    public record GithubIngestRequest(String url, String branch, String subpath) {}
}
