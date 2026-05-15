package com.envestnet.atlas.uplift.repo;

import com.envestnet.atlas.uplift.domain.StranglerStepRow;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StranglerStepRepository extends CrudRepository<StranglerStepRow, UUID> {

    @Query("""
        SELECT * FROM uplift.strangler_step
         WHERE project_id = :pid
         ORDER BY sequence_no
        """)
    List<StranglerStepRow> findByProject(@Param("pid") UUID projectId);

    @Query("SELECT * FROM uplift.strangler_step WHERE project_id = :pid AND module_id = :mid")
    Optional<StranglerStepRow> findByModule(@Param("pid") UUID projectId,
                                            @Param("mid") UUID moduleId);
}
