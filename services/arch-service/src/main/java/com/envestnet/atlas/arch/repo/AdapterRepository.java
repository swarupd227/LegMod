package com.envestnet.atlas.arch.repo;

import com.envestnet.atlas.arch.domain.AdapterRow;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AdapterRepository extends CrudRepository<AdapterRow, UUID> {
    @Query("SELECT * FROM arch.adapter WHERE project_id = :pid")
    List<AdapterRow> findByProject(@Param("pid") UUID projectId);

    @Modifying
    @Query("DELETE FROM arch.adapter WHERE project_id = :pid")
    void deleteByProject(@Param("pid") UUID projectId);
}
