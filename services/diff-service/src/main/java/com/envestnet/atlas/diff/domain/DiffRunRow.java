package com.envestnet.atlas.diff.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table(schema = "diff", value = "run")
public record DiffRunRow(
        @Id UUID id,
        UUID projectId,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String status,
        Integer envelopesReplayed,
        Integer passCount,
        Integer benignCount,
        Integer amberCount,
        Integer redCount,
        Boolean isPromoted,
        @ReadOnlyProperty String summary
) {}
