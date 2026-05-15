package com.envestnet.atlas.uplift.repo;

import com.envestnet.atlas.uplift.domain.FindingRow;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface FindingRepository extends CrudRepository<FindingRow, UUID> {
    @Query("""
        SELECT * FROM uplift.finding
         WHERE run_id = :rid
         ORDER BY
           CASE severity WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END,
           rule_id, file_path, line_start
        """)
    List<FindingRow> findByRun(@Param("rid") UUID runId);

    @Query("SELECT * FROM uplift.finding WHERE module_id = :mid ORDER BY rule_id, file_path, line_start")
    List<FindingRow> findByModule(@Param("mid") UUID moduleId);

    /**
     * Per-rule counts. Spring Data JDBC doesn't support interface projections
     * (unlike JPA) — it tries to bean-instantiate the interface and fails.
     * A record with column-aliased components works.
     */
    @Query("""
        SELECT rule_id AS ruleId, count(*) AS c FROM uplift.finding
         WHERE run_id = :rid
         GROUP BY rule_id ORDER BY c DESC
        """)
    List<RuleCount> ruleCounts(@Param("rid") UUID runId);

    record RuleCount(String ruleId, long c) {}
}
