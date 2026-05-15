package com.envestnet.atlas.recon.repo;

import com.envestnet.atlas.recon.domain.DecisionRow;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DecisionRepository extends CrudRepository<DecisionRow, UUID> {

    @Query("""
        SELECT * FROM recon.decision
         WHERE project_id = :pid
         ORDER BY
           CASE resolution WHEN 'pending' THEN 0 WHEN 'escalated' THEN 1 ELSE 2 END,
           CASE impact WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END,
           path
        """)
    List<DecisionRow> findByProject(@Param("pid") UUID projectId);

    @Modifying
    @Query("DELETE FROM recon.decision WHERE project_id = :pid")
    void deleteByProject(@Param("pid") UUID projectId);
}
