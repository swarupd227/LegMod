package com.envestnet.atlas.prj.repo;

import com.envestnet.atlas.prj.domain.Gate;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface GateRepository extends CrudRepository<Gate, UUID> {
    @Query("SELECT * FROM prj.gate WHERE project_id = :pid ORDER BY label")
    List<Gate> findByProject(@Param("pid") UUID projectId);
}
