package com.envestnet.atlas.reports.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table(schema = "reports", value = "bundle")
public record BundleRow(
        @Id UUID id,
        UUID projectId,
        OffsetDateTime builtAt,
        String status,
        String bundleUri,
        String closureUri,
        Integer sizeBytes,
        Integer fileCount,
        String closureText,
        Boolean closureStub,
        @ReadOnlyProperty String summary
) {}
