package com.envestnet.atlas.uplift.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table(schema = "uplift", value = "char_case")
public record CharCaseRow(
        @Id UUID id,
        UUID runId,
        UUID moduleId,
        String testName,
        String testKind,
        String inputSummary,
        String legacyOutput,
        String newOutput,
        String bucket,
        String diffKind,
        String aiRecommendation,
        String triageState,
        String triagedBy,
        OffsetDateTime triagedAt
) {}
