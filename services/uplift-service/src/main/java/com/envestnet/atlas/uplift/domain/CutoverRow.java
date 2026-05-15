package com.envestnet.atlas.uplift.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table(schema = "uplift", value = "cutover")
public record CutoverRow(
        @Id UUID id,
        UUID projectId,
        UUID stepId,
        UUID moduleId,
        String state,
        Integer trafficPercent,
        OffsetDateTime cutoverDate,
        String rollbackPlan,
        String notes,
        @ReadOnlyProperty String checklist,
        String approvedBy,
        OffsetDateTime approvedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
