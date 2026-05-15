package com.envestnet.atlas.recon.repo;

import com.envestnet.atlas.recon.domain.ElementRow;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ElementRepository extends CrudRepository<ElementRow, UUID> {
    @Query("SELECT * FROM recon.element WHERE run_id = :rid")
    List<ElementRow> findByRun(@Param("rid") UUID runId);

    @Modifying
    @Query("DELETE FROM recon.element WHERE project_id = :pid")
    void deleteByProject(@Param("pid") UUID projectId);
}
