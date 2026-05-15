package com.envestnet.atlas.uplift.repo;

import com.envestnet.atlas.uplift.domain.CutoverRow;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CutoverRepository extends CrudRepository<CutoverRow, UUID> {

    @Query("""
        SELECT * FROM uplift.cutover
         WHERE project_id = :pid
         ORDER BY
           CASE state
             WHEN 'live' THEN 0
             WHEN 'canary' THEN 1
             WHEN 'shadow' THEN 2
             WHEN 'planned' THEN 3
             WHEN 'rolled_back' THEN 4
             ELSE 5
           END,
           created_at
        """)
    List<CutoverRow> findByProject(@Param("pid") UUID projectId);

    @Query("SELECT * FROM uplift.cutover WHERE project_id = :pid AND step_id = :sid")
    Optional<CutoverRow> findByStep(@Param("pid") UUID projectId,
                                     @Param("sid") UUID stepId);
}
