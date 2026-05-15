package com.envestnet.atlas.gen.repo;

import com.envestnet.atlas.gen.domain.GenRunRow;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GenRunRepository extends CrudRepository<GenRunRow, UUID> {
    @Query("SELECT * FROM gen.run WHERE project_id = :pid ORDER BY started_at DESC")
    List<GenRunRow> findByProject(@Param("pid") UUID projectId);

    @Query("SELECT * FROM gen.run WHERE project_id = :pid ORDER BY started_at DESC LIMIT 1")
    Optional<GenRunRow> latest(@Param("pid") UUID projectId);
}
