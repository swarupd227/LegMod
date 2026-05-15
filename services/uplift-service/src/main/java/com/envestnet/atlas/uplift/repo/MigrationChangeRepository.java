package com.envestnet.atlas.uplift.repo;

import com.envestnet.atlas.uplift.domain.MigrationChangeRow;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MigrationChangeRepository extends CrudRepository<MigrationChangeRow, UUID> {

    @Query("SELECT * FROM uplift.migration_change WHERE run_id = :rid ORDER BY changes DESC, file_path")
    List<MigrationChangeRow> findByRun(@Param("rid") UUID runId);

    @Query("""
        SELECT * FROM uplift.migration_change
         WHERE run_id = :rid AND file_path = :path
         LIMIT 1
        """)
    Optional<MigrationChangeRow> findByRunAndPath(@Param("rid") UUID runId,
                                                  @Param("path") String filePath);
}
