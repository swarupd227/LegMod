package com.envestnet.atlas.arch.repo;

import com.envestnet.atlas.arch.domain.NarrativeRow;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface NarrativeRepository extends CrudRepository<NarrativeRow, UUID> {
    @Query("SELECT * FROM arch.narrative WHERE operation_id = :oid ORDER BY created_at DESC LIMIT 1")
    Optional<NarrativeRow> findLatestForOperation(@Param("oid") UUID operationId);
}
