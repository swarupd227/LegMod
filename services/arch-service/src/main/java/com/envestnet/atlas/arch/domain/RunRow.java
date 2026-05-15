package com.envestnet.atlas.arch.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table(schema = "arch", value = "run")
public record RunRow(
        @Id UUID id,
        UUID projectId,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String status,
        String sourcePath,
        // jsonb column with server-side default; updated via JdbcTemplate with ::jsonb cast.
        @ReadOnlyProperty String summary
) {}
