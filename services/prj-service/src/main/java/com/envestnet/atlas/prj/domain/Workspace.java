package com.envestnet.atlas.prj.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table(schema = "prj", value = "workspace")
public record Workspace(
        @Id UUID id,
        String name,
        String ownerEmail,
        OffsetDateTime createdAt
) {}
