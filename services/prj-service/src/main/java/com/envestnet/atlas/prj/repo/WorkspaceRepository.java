package com.envestnet.atlas.prj.repo;

import com.envestnet.atlas.prj.domain.Workspace;
import org.springframework.data.repository.CrudRepository;

import java.util.UUID;

public interface WorkspaceRepository extends CrudRepository<Workspace, UUID> {}
