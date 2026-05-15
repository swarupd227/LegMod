package com.envestnet.atlas.prj.orchestration;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * Workflow implementation. Composes the two activities into the
 * canonical "run archaeology + advance gate" sequence with
 * conservative retry policies on the network-touching step.
 *
 * <p>Retry policy nuances:
 * <ul>
 *   <li>{@code runArchaeology} can take minutes — start-to-close
 *       timeout is generous (10 min). Three attempts with 5-second
 *       initial backoff covers transient arch-service restarts /
 *       gateway 502s.</li>
 *   <li>{@code advanceToStageB} is a fast DB write — 30-second
 *       timeout, 5 attempts with 200ms backoff. If we're getting
 *       a sustained DB write failure, the workflow gives up and
 *       reports it.</li>
 * </ul>
 *
 * <p>The {@link WorkflowImpl} annotation declares this implementation
 * to the Spring Boot starter, which registers it on the
 * {@code atlas-prj} task queue worker on app start.</p>
 */
@WorkflowImpl(taskQueues = ArchaeologyWorkflow.TASK_QUEUE)
public class ArchaeologyWorkflowImpl implements ArchaeologyWorkflow {

    private final ArchaeologyActivities longRunningActivities =
            Workflow.newActivityStub(
                    ArchaeologyActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMinutes(10))
                            .setRetryOptions(RetryOptions.newBuilder()
                                    .setInitialInterval(Duration.ofSeconds(5))
                                    .setMaximumInterval(Duration.ofMinutes(1))
                                    .setMaximumAttempts(3)
                                    .build())
                            .build());

    private final ArchaeologyActivities quickActivities =
            Workflow.newActivityStub(
                    ArchaeologyActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofSeconds(30))
                            .setRetryOptions(RetryOptions.newBuilder()
                                    .setInitialInterval(Duration.ofMillis(200))
                                    .setMaximumAttempts(5)
                                    .build())
                            .build());

    @Override
    public ArchaeologyResult run(ArchaeologyInput input) {
        try {
            String runId = longRunningActivities.runArchaeology(
                    input.projectId(), input.sourcePath());

            quickActivities.advanceToStageB(input.projectId(), input.actorEmail());

            return new ArchaeologyResult(
                    input.projectId(), runId, "completed", null);
        } catch (ActivityFailure e) {
            // Activity exhausted retries. The workflow itself doesn't
            // retry — the SPA can re-submit if it wants — but we
            // surface a structured failure rather than the raw
            // ActivityFailure so the result shape is stable.
            return new ArchaeologyResult(
                    input.projectId(),
                    null,
                    "failed",
                    e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
        }
    }
}
