package com.envestnet.atlas.prj.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table(schema = "prj", value = "gate")
public record Gate(
        @Id UUID id,
        UUID projectId,
        String label,
        String state,
        OffsetDateTime transitionedAt,
        String transitionedBy
) {}
