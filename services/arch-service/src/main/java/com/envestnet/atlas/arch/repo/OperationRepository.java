package com.envestnet.atlas.arch.repo;

import com.envestnet.atlas.arch.domain.OperationRow;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperationRepository extends CrudRepository<OperationRow, UUID> {
    @Query("SELECT * FROM arch.operation WHERE project_id = :pid ORDER BY name")
    List<OperationRow> findByProject(@Param("pid") UUID projectId);

    @Query("SELECT * FROM arch.operation WHERE project_id = :pid AND namespace = :ns AND name = :nm")
    Optional<OperationRow> findOne(@Param("pid") UUID projectId,
                                   @Param("ns") String namespace,
                                   @Param("nm") String name);

    @Modifying
    @Query("DELETE FROM arch.operation WHERE project_id = :pid")
    void deleteByProject(@Param("pid") UUID projectId);
}
