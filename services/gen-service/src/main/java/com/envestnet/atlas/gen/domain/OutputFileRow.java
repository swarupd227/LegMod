package com.envestnet.atlas.gen.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table(schema = "gen", value = "output_file")
public record OutputFileRow(
        @Id UUID id,
        UUID runId,
        UUID projectId,
        String path,
        Integer sizeBytes,
        String storageUri
) {}
