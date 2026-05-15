package com.envestnet.atlas.recon.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table(schema = "recon", value = "element")
public record ElementRow(
        @Id UUID id,
        UUID projectId,
        UUID runId,
        String path,
        // jsonb columns — written via JdbcTemplate with ::jsonb cast.
        @ReadOnlyProperty String vendorView,
        @ReadOnlyProperty String codeView,
        @ReadOnlyProperty String empiricView
) {}
