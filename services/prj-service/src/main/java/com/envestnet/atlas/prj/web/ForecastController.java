package com.envestnet.atlas.prj.web;

import com.envestnet.atlas.prj.auth.GatewayIdentity;
import com.envestnet.atlas.prj.auth.WorkspaceAccess;
import com.envestnet.atlas.prj.auth.WorkspaceAccessDeniedException;
import com.envestnet.atlas.prj.domain.Project;
import com.envestnet.atlas.prj.forecast.ForecastService;
import com.envestnet.atlas.prj.repo.ProjectRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Migration Forecast — the pre-Stage-A effort estimate that grounds
 * customer conversations *before* any Stage A LLM calls are paid for.
 *
 * <ul>
 *   <li>{@code POST /api/v1/projects/{id}/forecast/run} — invoke the
 *       Migration Forecast agent. Calls arch-service's structural peek,
 *       feeds the facts to the LLM gateway with a calibrated effort
 *       prompt, and persists the result. Idempotent — re-running replaces
 *       the prior estimate.</li>
 *   <li>{@code GET /api/v1/projects/{id}/forecast} — return the latest
 *       persisted forecast, or 404 if none has been run.</li>
 * </ul>
 *
 * Both endpoints flow through the Phase-2K workspace gate. Read needs at
 * least VIEWER access; running the forecast requires EDITOR (because it
 * spends LLM budget on the workspace's behalf).
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ForecastController {

    private final ForecastService forecasts;
    private final ProjectRepository projects;
    private final WorkspaceAccess access;

    public ForecastController(ForecastService forecasts,
                              ProjectRepository projects,
                              WorkspaceAccess access) {
        this.forecasts = forecasts;
        this.projects = projects;
        this.access = access;
    }

    @PostMapping("/{id}/forecast/run")
    public ResponseEntity<?> run(@PathVariable UUID id) {
        Project p = requireWritable(id);
        if (p == null) return ResponseEntity.notFound().build();
        if (p.sourcePath() == null || p.sourcePath().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "sourcePath",
                    "message", "Ingest a GitHub repo or upload a zip before running the forecast."));
        }
        try {
            Map<String, Object> result = forecasts.run(id);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/forecast")
    public ResponseEntity<?> get(@PathVariable UUID id) {
        if (!requireReadable(id)) return ResponseEntity.notFound().build();
        return forecasts.find(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

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

    private boolean requireReadable(UUID id) {
        Optional<Project> maybe = projects.findById(id);
        if (maybe.isEmpty()) return false;
        String email = GatewayIdentity.currentEmail().orElse(null);
        return access.canRead(email, maybe.get().workspaceId());
    }
}
