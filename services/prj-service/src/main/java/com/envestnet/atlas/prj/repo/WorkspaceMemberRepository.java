package com.envestnet.atlas.prj.repo;

import com.envestnet.atlas.prj.domain.WorkspaceMember;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceMemberRepository extends CrudRepository<WorkspaceMember, UUID> {

    /**
     * Look up a specific user's role in a specific workspace. The primary
     * authorization query — called on every project-touching request via
     * {@code WorkspaceAccess}.
     */
    @Query("SELECT * FROM prj.workspace_member " +
           "WHERE workspace_id = :wsId AND lower(user_email) = lower(:email)")
    Optional<WorkspaceMember> findByWorkspaceAndUser(@Param("wsId") UUID workspaceId,
                                                    @Param("email") String userEmail);

    /**
     * All workspaces a given user can see. Used to filter
     * {@code GET /api/v1/workspaces} so users only see workspaces they're
     * a member of — never the full table.
     */
    @Query("SELECT * FROM prj.workspace_member WHERE lower(user_email) = lower(:email)")
    List<WorkspaceMember> findByUserEmail(@Param("email") String userEmail);

    /**
     * Roster of a single workspace — drives the future "Members" admin
     * tab in the SPA. Already wired so the controller can render members
     * without a follow-up migration.
     */
    @Query("SELECT * FROM prj.workspace_member WHERE workspace_id = :wsId " +
           "ORDER BY granted_at ASC")
    List<WorkspaceMember> findByWorkspace(@Param("wsId") UUID workspaceId);
}
