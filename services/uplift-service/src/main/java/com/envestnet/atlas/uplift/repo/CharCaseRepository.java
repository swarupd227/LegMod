package com.envestnet.atlas.uplift.repo;

import com.envestnet.atlas.uplift.domain.CharCaseRow;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CharCaseRepository extends CrudRepository<CharCaseRow, UUID> {

    @Query("""
        SELECT * FROM uplift.char_case
         WHERE run_id = :rid
         ORDER BY
           CASE bucket WHEN 'regression' THEN 0 WHEN 'benign' THEN 1 ELSE 2 END,
           module_id, test_name
        """)
    List<CharCaseRow> findByRun(@Param("rid") UUID runId);

    @Query("""
        SELECT * FROM uplift.char_case
         WHERE run_id = :rid AND bucket = :bucket
         ORDER BY module_id, test_name
        """)
    List<CharCaseRow> findByBucket(@Param("rid") UUID runId,
                                    @Param("bucket") String bucket);
}
