package com.envestnet.atlas.diff.repo;

import com.envestnet.atlas.diff.domain.DivergenceRow;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DivergenceRepository extends CrudRepository<DivergenceRow, UUID> {

    @Query("""
        SELECT d.* FROM diff.divergence d
          JOIN diff.replay r ON r.id = d.replay_id
         WHERE r.run_id = :rid
         ORDER BY
           CASE d.bucket WHEN 'red' THEN 0 WHEN 'amber' THEN 1 ELSE 2 END,
           d.operation_name, d.kind
        """)
    List<DivergenceRow> findByRun(@Param("rid") UUID runId);

    @Query("SELECT * FROM diff.divergence WHERE replay_id = :rid ORDER BY kind")
    List<DivergenceRow> findByReplay(@Param("rid") UUID replayId);

    @Query("""
        SELECT count(*) FROM diff.divergence d
          JOIN diff.replay r ON r.id = d.replay_id
         WHERE r.project_id = :pid AND d.bucket = :bucket
        """)
    long countByBucket(@Param("pid") UUID projectId, @Param("bucket") String bucket);
}
