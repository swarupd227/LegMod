package com.envestnet.atlas.reports.repo;

import com.envestnet.atlas.reports.domain.BundleRow;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BundleRepository extends CrudRepository<BundleRow, UUID> {
    @Query("SELECT * FROM reports.bundle WHERE project_id = :pid ORDER BY built_at DESC")
    List<BundleRow> findByProject(@Param("pid") UUID projectId);

    @Query("SELECT * FROM reports.bundle WHERE project_id = :pid ORDER BY built_at DESC LIMIT 1")
    Optional<BundleRow> latest(@Param("pid") UUID projectId);
}
