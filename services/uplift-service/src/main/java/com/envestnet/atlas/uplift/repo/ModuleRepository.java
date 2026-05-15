package com.envestnet.atlas.uplift.repo;

import com.envestnet.atlas.uplift.domain.ModuleRow;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ModuleRepository extends CrudRepository<ModuleRow, UUID> {
    @Query("SELECT * FROM uplift.module WHERE run_id = :rid ORDER BY difficulty DESC, loc DESC")
    List<ModuleRow> findByRun(@Param("rid") UUID runId);
}
