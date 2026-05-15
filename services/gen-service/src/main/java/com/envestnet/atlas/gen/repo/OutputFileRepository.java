package com.envestnet.atlas.gen.repo;

import com.envestnet.atlas.gen.domain.OutputFileRow;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutputFileRepository extends CrudRepository<OutputFileRow, UUID> {
    @Query("SELECT * FROM gen.output_file WHERE run_id = :rid ORDER BY path")
    List<OutputFileRow> findByRun(@Param("rid") UUID runId);

    @Modifying
    @Query("DELETE FROM gen.output_file WHERE run_id = :rid")
    void deleteByRun(@Param("rid") UUID runId);
}
