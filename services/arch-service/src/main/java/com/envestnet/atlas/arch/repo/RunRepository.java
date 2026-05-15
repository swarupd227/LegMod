package com.envestnet.atlas.arch.repo;

import com.envestnet.atlas.arch.domain.RunRow;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RunRepository extends CrudRepository<RunRow, UUID> {
    @Query("SELECT * FROM arch.run WHERE project_id = :pid ORDER BY started_at DESC")
    List<RunRow> findByProject(@Param("pid") UUID projectId);
}
