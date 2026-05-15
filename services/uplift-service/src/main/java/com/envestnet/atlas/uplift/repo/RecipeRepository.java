package com.envestnet.atlas.uplift.repo;

import com.envestnet.atlas.uplift.domain.RecipeRow;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecipeRepository extends CrudRepository<RecipeRow, UUID> {

    @Query("""
        SELECT * FROM uplift.recipe
         WHERE project_id = :pid
         ORDER BY
           CASE status WHEN 'accepted' THEN 0 WHEN 'proposed' THEN 1 ELSE 2 END,
           kind, label
        """)
    List<RecipeRow> findByProject(@Param("pid") UUID projectId);

    @Query("SELECT * FROM uplift.recipe WHERE project_id = :pid AND recipe_id = :rid")
    Optional<RecipeRow> findOne(@Param("pid") UUID projectId, @Param("rid") String recipeId);
}
