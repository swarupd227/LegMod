package com.envestnet.atlas.gen.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table(schema = "gen", value = "run")
public record GenRunRow(
        @Id UUID id,
        UUID projectId,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String status,
        String aWsdlUri,
        String bindingsUri,
        String outputUri,
        Integer fileCount,
        Integer errorCount,
        Integer warningCount,
        String logText,
        @ReadOnlyProperty String summary
) {}
