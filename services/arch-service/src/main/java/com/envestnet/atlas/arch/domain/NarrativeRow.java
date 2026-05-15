package com.envestnet.atlas.arch.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table(schema = "arch", value = "narrative")
public record NarrativeRow(
        @Id UUID id,
        UUID operationId,
        String text,
        String model,
        Integer tokensIn,
        Integer tokensOut,
        Integer latencyMs,
        boolean stub,
        OffsetDateTime createdAt
) {}
