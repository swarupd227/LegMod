package com.envestnet.atlas.recon.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table(schema = "recon", value = "decision")
public record DecisionRow(
        @Id UUID id,
        UUID projectId,
        UUID runId,
        UUID elementId,
        String path,
        String kind,
        String impact,
        String agentAction,
        String agentRationale,
        String[] agentAlts,
        String agentModel,
        Boolean agentStub,
        String confidence,
        String resolution,
        String chosenAction,
        @ReadOnlyProperty String chosenPayload,
        String note,
        String resolvedBy,
        OffsetDateTime resolvedAt,
        OffsetDateTime createdAt
) {}
