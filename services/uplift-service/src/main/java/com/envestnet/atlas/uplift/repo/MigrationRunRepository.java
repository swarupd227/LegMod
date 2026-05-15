package com.envestnet.atlas.uplift.repo;

import com.envestnet.atlas.uplift.domain.MigrationRunRow;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MigrationRunRepository extends CrudRepository<MigrationRunRow, UUID> {

    @Query("""
        SELECT * FROM uplift.migration_run
         WHERE step_id = :sid
         ORDER BY started_at DESC
         LIMIT 1
        """)
    Optional<MigrationRunRow> latestForStep(@Param("sid") UUID stepId);

    @Query("""
        SELECT * FROM uplift.migration_run
         WHERE project_id = :pid
         ORDER BY started_at DESC
        """)
    List<MigrationRunRow> findByProject(@Param("pid") UUID projectId);
}
