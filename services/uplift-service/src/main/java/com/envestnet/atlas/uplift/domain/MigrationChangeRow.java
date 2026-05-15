package com.envestnet.atlas.uplift.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table(schema = "uplift", value = "migration_change")
public record MigrationChangeRow(
        @Id UUID id,
        UUID runId,
        String filePath,
        String recipeId,
        Integer changes,
        String diffText
) {}
