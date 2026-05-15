package com.envestnet.atlas.recon.repo;

import com.envestnet.atlas.recon.domain.RunRow;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunRepository extends CrudRepository<RunRow, UUID> {
    @Query("SELECT * FROM recon.run WHERE project_id = :pid ORDER BY started_at DESC")
    List<RunRow> findByProject(@Param("pid") UUID projectId);

    @Query("SELECT * FROM recon.run WHERE project_id = :pid ORDER BY started_at DESC LIMIT 1")
    Optional<RunRow> latest(@Param("pid") UUID projectId);
}
