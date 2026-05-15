package com.envestnet.atlas.prj.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;

/**
 * Thrown when the current user lacks the required workspace-level role
 * for a mutating operation. Mapped to HTTP 403 by the
 * {@code @ResponseStatus} annotation.
 *
 * <p>Use this for <b>writes</b> only — the path the caller hit names a
 * real workspace, just one they're not allowed to write to. For
 * <b>reads</b>, prefer returning 404 from the controller to avoid
 * leaking workspace existence to a probing caller.</p>
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class WorkspaceAccessDeniedException extends RuntimeException {

    public WorkspaceAccessDeniedException(String userEmail,
                                            UUID workspaceId,
                                            WorkspaceAccess.Level required) {
        super("user '" + userEmail + "' lacks " + required
                + " on workspace " + workspaceId);
    }
}
