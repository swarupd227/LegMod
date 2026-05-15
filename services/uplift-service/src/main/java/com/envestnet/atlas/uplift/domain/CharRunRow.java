package com.envestnet.atlas.uplift.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table(schema = "uplift", value = "char_run")
public record CharRunRow(
        @Id UUID id,
        UUID projectId,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String status,
        Integer sampleSize,
        Integer passCount,
        Integer benignCount,
        Integer regressionCount,
        String errorText
) {}
