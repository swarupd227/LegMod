package com.envestnet.atlas.prj.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pre-Stage-A migration forecast. One row per project.
 *
 * `topRisks` and `structuralFacts` are jsonb on the server; we model them
 * as read-only Strings so Spring Data JDBC won't try to write a Java
 * String into a jsonb column on save. Writes happen via the
 * {@code ForecastService} which uses raw JDBC and casts the payload to
 * jsonb on the SQL side.
 */
@Table(schema = "prj", value = "forecast")
public record Forecast(
        @Id UUID projectId,
        OffsetDateTime generatedAt,
        Integer estimatedWeeks,
        String confidence,                            // LOW | MEDIUM | HIGH
        @ReadOnlyProperty String topRisks,            // JSON array
        String rationale,
        @ReadOnlyProperty String structuralFacts,     // JSON object
        String model,
        BigDecimal costUsd,
        Long latencyMs
) {}
