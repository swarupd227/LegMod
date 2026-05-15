package com.envestnet.atlas.diff.repo;

import com.envestnet.atlas.diff.domain.ReplayRow;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ReplayRepository extends CrudRepository<ReplayRow, UUID> {
    @Query("SELECT * FROM diff.replay WHERE run_id = :rid ORDER BY bucket, operation_name")
    List<ReplayRow> findByRun(@Param("rid") UUID runId);

    @Query("""
        SELECT bucket, count(*) AS c
          FROM diff.replay WHERE run_id = :rid
         GROUP BY bucket
        """)
    List<BucketCount> bucketCounts(@Param("rid") UUID runId);

    interface BucketCount {
        String getBucket();
        long   getC();
    }
}
