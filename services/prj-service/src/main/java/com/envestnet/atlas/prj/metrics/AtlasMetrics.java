package com.envestnet.atlas.prj.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Atlas-specific business metrics surfaced through Micrometer →
 * {@code /actuator/prometheus}. Centralised so the metric names + label
 * vocabulary live in one place; callers don't construct
 * {@link Counter} instances directly.
 *
 * <p>Counters in this class follow the Prometheus naming convention:
 * lowercase, snake_case, plural noun, {@code _total} suffix.</p>
 */
@Component
public class AtlasMetrics {

    /**
     * {@code atlas_stage_finalize_total{stage,state}} — incremented on
     * every gate transition. {@code state} is one of {@code passed},
     * {@code in_progress}, {@code failed}, etc.
     */
    private static final String STAGE_FINALIZE = "atlas_stage_finalize_total";

    private final MeterRegistry registry;

    /**
     * Cache the resolved {@link Counter} per (stage, state) pair so we
     * don't pay the registry-lookup cost on every advance. Counter
     * lookup is thread-safe but does take a small lock; this map dodges
     * that for the hot path.
     */
    private final ConcurrentMap<String, Counter> stageFinalizeCounters = new ConcurrentHashMap<>();

    public AtlasMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Records that a gate transition happened. Safe to call from any thread. */
    public void gateAdvanced(String stage, String state) {
        String key = stage + "/" + state;
        stageFinalizeCounters.computeIfAbsent(key, k -> Counter.builder(STAGE_FINALIZE)
                .description("Number of gate transitions, by stage and resulting state")
                .tag("stage", stage)
                .tag("state", state)
                .register(registry)).increment();
    }
}
