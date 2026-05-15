package com.envestnet.atlas.uplift.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One step in the strangler migration plan: extract a single module from the
 * monolith. recipeIds is JSONB; managed via raw JdbcTemplate.
 */
@Table(schema = "uplift", value = "strangler_step")
public record StranglerStepRow(
        @Id UUID id,
        UUID projectId,
        UUID moduleId,
        Integer sequenceNo,
        String status,
        String facadeNotes,
        @ReadOnlyProperty String recipeIds,
        String decidedBy,
        OffsetDateTime decidedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
