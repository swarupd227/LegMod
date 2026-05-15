package com.envestnet.atlas.cap.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table(schema = "cap", value = "deployment")
public record Deployment(
        @Id UUID id,
        UUID projectId,
        String environment,
        String method,
        String status,                 // pending | live | paused | stopped | failed
        Integer sampleRate,
        Integer maxPayload,
        String sanitizationV,
        OffsetDateTime startedAt,
        OffsetDateTime stoppedAt,
        OffsetDateTime lastSeen,
        OffsetDateTime createdAt
) {}
