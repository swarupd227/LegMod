package com.envestnet.atlas.uplift.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table(schema = "uplift", value = "module")
public record ModuleRow(
        @Id UUID id,
        UUID projectId,
        UUID runId,
        String name,
        String packageName,
        Integer fileCount,
        Integer loc,
        Integer difficulty,
        Integer findingCount
) {}
