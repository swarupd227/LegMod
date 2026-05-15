package com.envestnet.atlas.cap.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table(schema = "cap", value = "sanitization_rule")
public record SanitizationRule(
        @Id UUID id,
        UUID projectId,
        String name,
        String pattern,
        String strategy,
        Long hits,
        Boolean enabled
) {}
