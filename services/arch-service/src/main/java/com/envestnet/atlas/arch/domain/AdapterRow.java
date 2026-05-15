package com.envestnet.atlas.arch.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table(schema = "arch", value = "adapter")
public record AdapterRow(
        @Id UUID id,
        UUID projectId,
        String fqn,
        String kind,
        String pattern,
        int[] sourceLines
) {}
