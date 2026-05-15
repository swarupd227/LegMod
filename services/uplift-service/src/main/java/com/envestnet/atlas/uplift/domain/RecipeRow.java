package com.envestnet.atlas.uplift.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per (project, recipe-id). Curated by the user during Stage B; the
 * status field drives the gate (need >=1 accepted).
 *
 * findingIds / moduleIds are JSONB strings; marked read-only so Spring Data JDBC
 * skips them on INSERT/UPDATE. We maintain them via raw JdbcTemplate with ?::jsonb.
 */
@Table(schema = "uplift", value = "recipe")
public record RecipeRow(
        @Id UUID id,
        UUID projectId,
        String recipeId,
        String label,
        String description,
        String kind,
        String status,
        String notes,
        @ReadOnlyProperty String findingIds,
        @ReadOnlyProperty String moduleIds,
        String decidedBy,
        OffsetDateTime decidedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
