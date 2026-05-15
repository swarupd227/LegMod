package com.envestnet.atlas.prj.orchestration;

import com.envestnet.atlas.prj.domain.Gate;
import com.envestnet.atlas.prj.domain.Project;
import com.envestnet.atlas.prj.repo.GateRepository;
import com.envestnet.atlas.prj.repo.ProjectRepository;
import io.temporal.spring.boot.ActivityImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Activity worker — runs on prj-service's Temporal worker pool. The
 * methods do the actual HTTP / DB work; the workflow ({@link
 * ArchaeologyWorkflowImpl}) sequences them with retry policies.
 *
 * <p>These methods MUST be idempotent. Temporal will re-invoke them
 * on retry (e.g. if the worker crashes between writing the gate row
 * and reporting completion to the Temporal cluster). Both methods
 * here are safe to re-run: {@code arch-service}'s endpoint is
 * idempotent by project id, and the gate-advance update is an upsert
 * by composite key.</p>
 */
@Component
@ActivityImpl(taskQueues = ArchaeologyWorkflow.TASK_QUEUE)
public class ArchaeologyActivitiesImpl implements ArchaeologyActivities {

    private static final Logger log = LoggerFactory.getLogger(ArchaeologyActivitiesImpl.class);

    private final RestTemplate http;
    private final ProjectRepository projects;
    private final GateRepository gates;
    private final String archUrl;

    public ArchaeologyActivitiesImpl(
            RestTemplate http,
            ProjectRepository projects,
            GateRepository gates,
            @Value("${ARCH_SERVICE_URL:http://arch-service:8083}") String archUrl) {
        this.http = http;
        this.projects = projects;
        this.gates = gates;
        this.archUrl = archUrl;
    }

    @Override
    public String runArchaeology(String projectId, String sourcePath) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "projectId",  projectId,
                "sourcePath", sourcePath);

        log.info("Activity: runArchaeology projectId={}", projectId);
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = http.postForObject(
                archUrl + "/internal/archaeology/run",
                new HttpEntity<>(body, h),
                Map.class);

        if (resp == null) {
            throw new IllegalStateException("arch-service returned null response");
        }
        Object runId = resp.get("runId");
        return runId == null ? "unknown" : runId.toString();
    }

    @Override
    public void advanceToStageB(String projectIdStr, String actorEmail) {
        UUID projectId = UUID.fromString(projectIdStr);
        Project p = projects.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("project not found: " + projectId));

        // Identical logic to ProjectController.advanceStage(p, "B")
        // but standalone — workflows have no HTTP request context, so
        // we can't pull actor from RequestContextHolder. The workflow
        // passes the actor explicitly.
        String actor = actorEmail == null || actorEmail.isBlank() ? "workflow" : actorEmail;

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
                "B", p.sourcePath(), p.metrics(),
                p.createdAt(), OffsetDateTime.now()
        );
        projects.save(updated);

        gates.findByProject(p.id()).stream()
                .filter(g -> "B".equals(g.label()))
                .findFirst().ifPresent(g -> gates.save(new Gate(
                        g.id(), g.projectId(), g.label(),
                        "in_progress", OffsetDateTime.now(), actor)));

        log.info("Activity: project {} advanced to Stage B by {}", projectId, actor);
    }
}
