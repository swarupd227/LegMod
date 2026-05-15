package com.envestnet.atlas.cap.repo;

import com.envestnet.atlas.cap.domain.Deployment;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DeploymentRepository extends CrudRepository<Deployment, UUID> {
    @Query("SELECT * FROM cap.deployment WHERE project_id = :pid ORDER BY environment")
    List<Deployment> findByProject(@Param("pid") UUID projectId);

    @Modifying
    @Query("UPDATE cap.deployment SET status='live', started_at=now(), last_seen=now() WHERE project_id=:pid")
    void markLive(@Param("pid") UUID projectId);

    @Modifying
    @Query("UPDATE cap.deployment SET status='stopped', stopped_at=now() WHERE project_id=:pid AND status='live'")
    void stopAll(@Param("pid") UUID projectId);
}
