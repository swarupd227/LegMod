package com.envestnet.atlas.arch.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table(schema = "arch", value = "operation")
public record OperationRow(
        @Id UUID id,
        UUID projectId,
        UUID runId,
        String namespace,
        String name,
        String soapStyle,
        String soapUse,
        String sourceClass,
        int[] sourceLines,
        String inputType,
        String outputType,
        String[] faultTypes,
        String[] flags,
        String confidence,
        String decisionState,
        String reviewedBy,
        OffsetDateTime reviewedAt,
        OffsetDateTime createdAt
) {}
