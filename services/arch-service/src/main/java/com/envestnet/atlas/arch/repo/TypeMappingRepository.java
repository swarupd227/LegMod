package com.envestnet.atlas.arch.repo;

import com.envestnet.atlas.arch.domain.TypeMappingRow;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TypeMappingRepository extends CrudRepository<TypeMappingRow, UUID> {
    @Query("SELECT * FROM arch.type_mapping WHERE operation_id = :oid")
    List<TypeMappingRow> findByOperation(@Param("oid") UUID operationId);
}
