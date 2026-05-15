package com.envestnet.atlas.prj.repo;

import com.envestnet.atlas.prj.domain.Project;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ProjectRepository extends CrudRepository<Project, UUID> {

    @Query("SELECT * FROM prj.project WHERE workspace_id = :wsId ORDER BY updated_at DESC")
    List<Project> findByWorkspace(@Param("wsId") UUID workspaceId);
}
