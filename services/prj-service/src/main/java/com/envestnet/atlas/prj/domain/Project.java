package com.envestnet.atlas.prj.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * `metrics` is a jsonb column with a server-side default ('{}'::jsonb).
 * It's marked read-only so Spring Data JDBC won't try to write the Java
 * String into a jsonb column on save (which Postgres rejects). Reintroduce
 * write support via a custom converter when a feature actually needs it.
 */
@Table(schema = "prj", value = "project")
public record Project(
        @Id UUID id,
        UUID workspaceId,
        String name,
        String description,
        String mode,                 // SOAP | UPLIFT
        String sourceFramework,
        String targetFramework,
        String targetJavaVersion,
        String vendorPartner,
        String owner,
        String riskTier,             // LOW | MEDIUM | HIGH | CRITICAL
        String currentStage,         // A | B | C | D | E | F
        String sourcePath,
        @ReadOnlyProperty String metrics,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
