package com.envestnet.atlas.prj.orchestration;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Atomic units of work the {@link ArchaeologyWorkflow} composes.
 *
 * <p>Each activity is independently retryable per the policy declared
 * in {@link ArchaeologyWorkflowImpl}. Activities should be idempotent
 * — Temporal may invoke them more than once across retries — so the
 * implementations rely on:
 * <ul>
 *   <li>arch-service's own idempotent {@code /internal/archaeology/run}
 *       (driven by the project id; running it twice for the same
 *       project produces the same artifact set);</li>
 *   <li>{@code GateRepository.save(...)} being a simple upsert by
 *       label.</li>
 * </ul>
 */
@ActivityInterface
public interface ArchaeologyActivities {

    /**
     * Synchronously call arch-service to run the archaeology
     * pipeline for the project. Returns arch-service's run-id so the
     * workflow can include it in the result.
     */
    @ActivityMethod
    String runArchaeology(String projectId, String sourcePath);

    /**
     * Advance the project's stage cursor from A → B (A passed, B
     * in_progress). Called after a successful archaeology run.
     */
    @ActivityMethod
    void advanceToStageB(String projectId, String actorEmail);
}
