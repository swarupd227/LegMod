package com.envestnet.atlas.uplift.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table(schema = "uplift", value = "migration_run")
public record MigrationRunRow(
        @Id UUID id,
        UUID projectId,
        UUID stepId,
        UUID moduleId,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String status,
        @ReadOnlyProperty String summary,
        String outputUri,
        String errorText
) {}
