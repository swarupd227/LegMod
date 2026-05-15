package com.envestnet.atlas.prj.orchestration;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Durable orchestration for "run archaeology on a project."
 *
 * <p>The synchronous HTTP fan-out it replaces (in {@code ProjectController.
 * runArchaeology}) had three problems Temporal solves:
 * <ul>
 *   <li><b>Long-running.</b> Archaeology on a non-trivial source tree
 *       routinely takes 30–120 seconds. The browser request times out
 *       around the same range; users see a generic "request failed"
 *       and have no way to know if the work is still happening
 *       server-side.</li>
 *   <li><b>Partial failure.</b> If arch-service succeeds but the
 *       follow-up gate-advance write to Postgres fails, the previous
 *       sync code left the project in an inconsistent state. A
 *       Temporal workflow's retry policy on the gate-advance activity
 *       makes the chain effectively atomic.</li>
 *   <li><b>Crash safety.</b> Restart prj-service mid-archaeology
 *       today and the in-flight HTTP call is lost. A workflow whose
 *       execution is checkpointed in Temporal resumes from the
 *       last completed activity.</li>
 * </ul>
 *
 * <p>The pattern is the canonical Temporal one: this interface
 * defines the workflow's contract (input, output, signals); the
 * {@link ArchaeologyWorkflowImpl} class implements it; the
 * controller submits it via {@link io.temporal.client.WorkflowClient}
 * and returns a workflow id the SPA polls against.</p>
 */
@WorkflowInterface
public interface ArchaeologyWorkflow {

    /** Task queue all archaeology workflows + activities run on. */
    String TASK_QUEUE = "atlas-prj";

    /**
     * Run the archaeology pipeline for a project.
     *
     * @return summary the SPA needs to render the kick-off receipt
     *         (runId, status) plus anything the workflow learned along
     *         the way. Long-running detail lives in the status endpoint.
     */
    @WorkflowMethod
    ArchaeologyResult run(ArchaeologyInput input);

    /** Inputs to the workflow. */
    record ArchaeologyInput(
            String projectId,
            String sourcePath,
            String actorEmail        // gateway-asserted, propagated for provenance
    ) {}

    /** Public result the SPA reads via WorkflowClient. */
    record ArchaeologyResult(
            String projectId,
            String runId,            // arch-service's run-id
            String status,           // "completed" | "failed"
            String errorText         // populated only on failed
    ) {}
}
