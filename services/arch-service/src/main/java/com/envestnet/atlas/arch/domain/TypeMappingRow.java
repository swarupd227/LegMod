package com.envestnet.atlas.arch.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table(schema = "arch", value = "type_mapping")
public record TypeMappingRow(
        @Id UUID id,
        UUID operationId,
        String fieldName,
        String javaType,
        String qnameNamespace,
        String qnameLocal,
        String adapterFqn,
        String notes
) {}
