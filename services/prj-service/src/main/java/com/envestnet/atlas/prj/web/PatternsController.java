package com.envestnet.atlas.prj.web;

import com.envestnet.atlas.prj.auth.GatewayIdentity;
import com.envestnet.atlas.prj.auth.WorkspaceAccess;
import com.envestnet.atlas.prj.domain.Project;
import com.envestnet.atlas.prj.patterns.PatternService;
import com.envestnet.atlas.prj.repo.ProjectRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cross-project Reconciliation Pattern Library — read-only API.
 *
 * <p>{@code GET /api/v1/projects/{id}/recon/patterns} returns the
 * patterns the Stage C SPA should overlay on its divergence cards as
 * "Recommended (based on N prior migrations)" hints. The list is
 * scoped to the project's vendor family, sorted by occurrence count
 * descending. Empty list when there are no matches.</p>
 */
@RestController
@RequestMapping("/api/v1/projects")
public class PatternsController {

    private final PatternService patterns;
    private final ProjectRepository projects;
    private final WorkspaceAccess access;

    public PatternsController(PatternService patterns,
                              ProjectRepository projects,
                              WorkspaceAccess access) {
        this.patterns = patterns;
        this.projects = projects;
        this.access = access;
    }

    @GetMapping("/{id}/recon/patterns")
    public ResponseEntity<?> list(@PathVariable UUID id) {
        Optional<Project> maybe = projects.findById(id);
        if (maybe.isEmpty()) return ResponseEntity.notFound().build();
        String email = GatewayIdentity.currentEmail().orElse(null);
        if (!access.canRead(email, maybe.get().workspaceId())) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> rows = patterns.findForProject(id);
        return ResponseEntity.ok(Map.of(
                "projectId", id,
                "count", rows.size(),
                "patterns", rows
        ));
    }
}
