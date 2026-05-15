package com.envestnet.atlas.diff.repo;

import com.envestnet.atlas.diff.domain.DiffRunRow;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiffRunRepository extends CrudRepository<DiffRunRow, UUID> {
    @Query("SELECT * FROM diff.run WHERE project_id = :pid ORDER BY started_at DESC")
    List<DiffRunRow> findByProject(@Param("pid") UUID projectId);

    @Query("SELECT * FROM diff.run WHERE project_id = :pid ORDER BY started_at DESC LIMIT 1")
    Optional<DiffRunRow> latest(@Param("pid") UUID projectId);
}
