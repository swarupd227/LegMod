package com.envestnet.atlas.uplift.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table(schema = "uplift", value = "scan_run")
public record ScanRunRow(
        @Id UUID id,
        UUID projectId,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String status,
        String sourcePath,
        @ReadOnlyProperty String summary
) {}
