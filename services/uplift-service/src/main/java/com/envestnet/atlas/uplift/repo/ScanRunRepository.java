package com.envestnet.atlas.uplift.repo;

import com.envestnet.atlas.uplift.domain.ScanRunRow;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ScanRunRepository extends CrudRepository<ScanRunRow, UUID> {
    @Query("SELECT * FROM uplift.scan_run WHERE project_id = :pid ORDER BY started_at DESC LIMIT 1")
    Optional<ScanRunRow> latest(@Param("pid") UUID projectId);
}
