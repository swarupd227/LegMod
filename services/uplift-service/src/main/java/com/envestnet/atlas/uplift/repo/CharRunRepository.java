package com.envestnet.atlas.uplift.repo;

import com.envestnet.atlas.uplift.domain.CharRunRow;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CharRunRepository extends CrudRepository<CharRunRow, UUID> {

    @Query("""
        SELECT * FROM uplift.char_run
         WHERE project_id = :pid
         ORDER BY started_at DESC
         LIMIT 1
        """)
    Optional<CharRunRow> latest(@Param("pid") UUID projectId);
}
