package com.envestnet.atlas.uplift.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table(schema = "uplift", value = "finding")
public record FindingRow(
        @Id UUID id,
        UUID projectId,
        UUID runId,
        UUID moduleId,
        String ruleId,
        String ruleLabel,
        String severity,
        String filePath,
        Integer lineStart,
        Integer lineEnd,
        String snippet,
        String suggestedRecipe
) {}
