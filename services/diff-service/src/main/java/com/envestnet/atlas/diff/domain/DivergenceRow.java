package com.envestnet.atlas.diff.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table(schema = "diff", value = "divergence")
public record DivergenceRow(
        @Id UUID id,
        UUID replayId,
        UUID projectId,
        String operationName,
        String kind,
        String bucket,
        String xpath,
        String legacyValue,
        String newValue,
        String humanSummary,
        String agentAction,
        String agentRationale,
        String agentModel,
        Boolean agentStub,
        @ReadOnlyProperty String autofixProposal,
        Boolean resolved,
        String resolvedBy,
        OffsetDateTime resolvedAt
) {}
