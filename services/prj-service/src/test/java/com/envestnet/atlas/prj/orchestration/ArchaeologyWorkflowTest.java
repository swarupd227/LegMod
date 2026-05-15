package com.envestnet.atlas.prj.orchestration;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In-memory exercise of {@link ArchaeologyWorkflow}.
 *
 * <p>Uses Temporal's {@link TestWorkflowEnvironment} so the whole
 * orchestration (workflow + activities + retry policy) runs without
 * needing a real Temporal cluster. The activities are stubbed via
 * an inline implementation so each test controls success / failure /
 * retry behavior independently.</p>
 */
class ArchaeologyWorkflowTest {

    private TestWorkflowEnvironment env;
    private Worker worker;
    private WorkflowClient client;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        worker = env.newWorker(ArchaeologyWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(ArchaeologyWorkflowImpl.class);
        client = env.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    private ArchaeologyWorkflow newWorkflowStub(String workflowId) {
        return client.newWorkflowStub(ArchaeologyWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(ArchaeologyWorkflow.TASK_QUEUE)
                        .setWorkflowId(workflowId)
                        .build());
    }

    @Test
    void happyPathRunsBothActivitiesInOrderAndReturnsCompleted() {
        AtomicInteger archCalls = new AtomicInteger();
        AtomicInteger advanceCalls = new AtomicInteger();

        worker.registerActivitiesImplementations(new ArchaeologyActivities() {
            @Override
            public String runArchaeology(String projectId, String sourcePath) {
                archCalls.incrementAndGet();
                return "run-abc-123";
            }
            @Override
            public void advanceToStageB(String projectId, String actorEmail) {
                // Workflow must call runArchaeology before advanceToStageB.
                assertThat(archCalls.get())
                        .as("advanceToStageB ran before runArchaeology")
                        .isGreaterThan(0);
                advanceCalls.incrementAndGet();
            }
        });
        env.start();

        var result = newWorkflowStub("hp-1").run(new ArchaeologyWorkflow.ArchaeologyInput(
                "11111111-1111-1111-1111-111111111111",
                "/legacy/src",
                "alice@envestnet.local"));

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.runId()).isEqualTo("run-abc-123");
        assertThat(result.projectId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(result.errorText()).isNull();
        assertThat(archCalls).hasValue(1);
        assertThat(advanceCalls).hasValue(1);
    }

    @Test
    void runArchaeologyIsRetriedOnTransientFailure() {
        AtomicInteger archCalls = new AtomicInteger();
        AtomicInteger advanceCalls = new AtomicInteger();

        worker.registerActivitiesImplementations(new ArchaeologyActivities() {
            @Override
            public String runArchaeology(String projectId, String sourcePath) {
                int n = archCalls.incrementAndGet();
                if (n == 1) throw new IllegalStateException("upstream 503");
                return "run-after-retry";
            }
            @Override
            public void advanceToStageB(String projectId, String actorEmail) {
                advanceCalls.incrementAndGet();
            }
        });
        env.start();

        var result = newWorkflowStub("retry-1").run(new ArchaeologyWorkflow.ArchaeologyInput(
                "pid", "/src", "alice@envestnet.local"));

        // Activity succeeded on retry; workflow saw a single successful
        // completion.
        assertThat(result.status()).isEqualTo("completed");
        assertThat(archCalls).hasValueGreaterThan(1);
        assertThat(advanceCalls).hasValue(1);
    }

    @Test
    void advanceToStageBIsNotInvokedWhenRunArchaeologyExhaustsRetries() {
        AtomicInteger archCalls = new AtomicInteger();
        AtomicInteger advanceCalls = new AtomicInteger();

        worker.registerActivitiesImplementations(new ArchaeologyActivities() {
            @Override
            public String runArchaeology(String projectId, String sourcePath) {
                archCalls.incrementAndGet();
                throw new IllegalStateException("upstream permanently down");
            }
            @Override
            public void advanceToStageB(String projectId, String actorEmail) {
                advanceCalls.incrementAndGet();   // must not happen
            }
        });
        env.start();

        var result = newWorkflowStub("fail-1").run(new ArchaeologyWorkflow.ArchaeologyInput(
                "pid", "/src", "alice@envestnet.local"));

        // Workflow caught the ActivityFailure and returned a structured
        // failure. Gate advance was not attempted — the project stays
        // at Stage A until the user re-submits.
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.runId()).isNull();
        assertThat(result.errorText()).contains("permanently down");
        assertThat(archCalls).hasValueGreaterThan(1);   // retried per policy
        assertThat(advanceCalls).hasValue(0);
    }

    @Test
    void actorEmailIsPropagatedToAdvanceActivity() {
        var capturedActor = new java.util.concurrent.atomic.AtomicReference<String>();

        worker.registerActivitiesImplementations(new ArchaeologyActivities() {
            @Override
            public String runArchaeology(String projectId, String sourcePath) {
                return "run-x";
            }
            @Override
            public void advanceToStageB(String projectId, String actorEmail) {
                capturedActor.set(actorEmail);
            }
        });
        env.start();

        newWorkflowStub("actor-1").run(new ArchaeologyWorkflow.ArchaeologyInput(
                "pid", "/src", "bob@envestnet.local"));

        assertThat(capturedActor.get()).isEqualTo("bob@envestnet.local");
    }
}
