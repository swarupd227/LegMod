package com.envestnet.atlas.cap.repo;

import com.envestnet.atlas.cap.domain.SanitizationRule;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SanitizationRuleRepository extends CrudRepository<SanitizationRule, UUID> {

    /** Default rule set (no project) plus rules pinned to this project. Enabled only — used by the sanitizer. */
    @Query("""
        SELECT * FROM cap.sanitization_rule
        WHERE (project_id IS NULL OR project_id = :pid) AND enabled
        ORDER BY name
        """)
    List<SanitizationRule> activeFor(@Param("pid") UUID projectId);

    /** Same scope as {@link #activeFor} but includes disabled rules so the UI can re-enable them. */
    @Query("""
        SELECT * FROM cap.sanitization_rule
        WHERE (project_id IS NULL OR project_id = :pid)
        ORDER BY name
        """)
    List<SanitizationRule> visibleFor(@Param("pid") UUID projectId);
}
