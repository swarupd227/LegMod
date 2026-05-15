package com.envestnet.atlas.cap.repo;

import com.envestnet.atlas.cap.domain.Envelope;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface EnvelopeRepository extends CrudRepository<Envelope, UUID> {
    @Query("SELECT * FROM cap.envelope WHERE project_id = :pid ORDER BY captured_at DESC LIMIT :limit")
    List<Envelope> recent(@Param("pid") UUID projectId, @Param("limit") int limit);

    @Query("SELECT count(*) FROM cap.envelope WHERE project_id = :pid")
    long countByProject(@Param("pid") UUID projectId);

    @Modifying
    @Query("DELETE FROM cap.envelope WHERE project_id = :pid")
    void deleteByProject(@Param("pid") UUID projectId);

    @Query("""
        SELECT * FROM cap.envelope
         WHERE deployment_id = :did
         ORDER BY captured_at DESC LIMIT :limit
        """)
    List<Envelope> recentForDeployment(@Param("did") UUID deploymentId,
                                       @Param("limit") int limit);

    @Query("SELECT count(*) FROM cap.envelope WHERE deployment_id = :did")
    long countByDeployment(@Param("did") UUID deploymentId);
}

