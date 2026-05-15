package com.envestnet.atlas.prj.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-unit coverage of the metric vocabulary surfaced through
 * {@link AtlasMetrics}. No Spring context — just the registry and the
 * counter contract.
 */
class AtlasMetricsTest {

    @Test
    void gateAdvancedRegistersTaggedCounter() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AtlasMetrics(registry);

        metrics.gateAdvanced("B", "passed");

        var c = registry.find("atlas_stage_finalize_total")
                        .tag("stage", "B")
                        .tag("state", "passed")
                        .counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);
    }

    @Test
    void multipleAdvancesIncrementTheSameCounter() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AtlasMetrics(registry);

        metrics.gateAdvanced("C", "passed");
        metrics.gateAdvanced("C", "passed");
        metrics.gateAdvanced("C", "passed");

        assertThat(registry.find("atlas_stage_finalize_total")
                .tag("stage", "C").tag("state", "passed").counter().count())
            .isEqualTo(3.0);
    }

    @Test
    void differentStageOrStatePartitionIntoDifferentCounters() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AtlasMetrics(registry);

        metrics.gateAdvanced("B", "passed");
        metrics.gateAdvanced("C", "passed");
        metrics.gateAdvanced("B", "in_progress");

        // Three distinct (stage, state) tuples → three counters.
        assertThat(registry.find("atlas_stage_finalize_total").counters()).hasSize(3);

        assertThat(registry.find("atlas_stage_finalize_total")
                .tag("stage", "B").tag("state", "passed").counter().count())
            .isEqualTo(1.0);
        assertThat(registry.find("atlas_stage_finalize_total")
                .tag("stage", "C").tag("state", "passed").counter().count())
            .isEqualTo(1.0);
        assertThat(registry.find("atlas_stage_finalize_total")
                .tag("stage", "B").tag("state", "in_progress").counter().count())
            .isEqualTo(1.0);
    }

    @Test
    void counterCarriesADescription() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AtlasMetrics(registry);
        metrics.gateAdvanced("A", "passed");

        var c = registry.find("atlas_stage_finalize_total").counter();
        assertThat(c.getId().getDescription())
            .isEqualTo("Number of gate transitions, by stage and resulting state");
    }
}
