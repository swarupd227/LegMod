package com.envestnet.atlas.diff.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table(schema = "diff", value = "replay")
public record ReplayRow(
        @Id UUID id,
        UUID runId,
        UUID projectId,
        UUID envelopeId,
        String operationName,
        String direction,
        String bucket,           // pass | benign | amber | red
        Integer diffCount,
        String firstKind,
        String summary
) {}
