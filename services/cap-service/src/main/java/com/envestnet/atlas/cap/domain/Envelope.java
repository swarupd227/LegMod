package com.envestnet.atlas.cap.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table(schema = "cap", value = "envelope")
public record Envelope(
        @Id UUID id,
        UUID projectId,
        UUID deploymentId,
        String operationName,
        String direction,              // REQUEST | RESPONSE | FAULT
        OffsetDateTime capturedAt,
        String partner,
        String environment,
        Integer sizeBytes,
        String storageUri,
        Integer sanitizationHits,
        String correlationId
) {}
