package com.envestnet.atlas.prj.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

/**
 * Reads the gateway-asserted identity headers from the inbound request.
 *
 * <p>The api-gateway's {@code IdentityForwardingFilter} stamps every
 * authenticated request with three headers:
 * {@value #HDR_EMAIL}, {@value #HDR_NAME}, {@value #HDR_ROLES}. Inside
 * a controller, those headers are the authoritative source of "who is
 * doing this." Anything else — body fields, query parameters — is
 * spoofable and must NOT be trusted as identity.</p>
 *
 * <p>The helper degrades gracefully when called outside an HTTP request
 * context (background tasks, scheduled jobs, tests) — every method
 * returns the documented fallback rather than throwing.</p>
 */
public final class GatewayIdentity {

    public static final String HDR_EMAIL = "X-Atlas-User-Email";
    public static final String HDR_NAME  = "X-Atlas-User-Name";
    public static final String HDR_ROLES = "X-Atlas-User-Roles";

    private GatewayIdentity() {}

    /** Email of the signed-in user, or empty if no auth context. */
    public static Optional<String> currentEmail() {
        return header(HDR_EMAIL);
    }

    /** Display name of the signed-in user, or empty if no auth context. */
    public static Optional<String> currentName() {
        return header(HDR_NAME);
    }

    /** Roles list (gateway sends comma-separated), or empty list if absent. */
    public static List<String> currentRoles() {
        return header(HDR_ROLES)
                .map(s -> List.of(s.split(",")))
                .orElse(List.of());
    }

    /**
     * Best-effort identity for audit fields like {@code transitionedBy}
     * or {@code decidedBy}. Prefers the header, falls back to a
     * caller-supplied value (typically a request-body field), then to
     * {@code "system"} so audit columns never go null.
     */
    public static String resolveActor(String fallbackFromBody) {
        return currentEmail()
                .filter(s -> !s.isBlank())
                .orElseGet(() -> {
                    if (fallbackFromBody != null && !fallbackFromBody.isBlank()) {
                        return fallbackFromBody;
                    }
                    return "system";
                });
    }

    private static Optional<String> header(String name) {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (!(attrs instanceof ServletRequestAttributes sra)) return Optional.empty();
            HttpServletRequest req = sra.getRequest();
            String v = req.getHeader(name);
            return (v == null || v.isBlank()) ? Optional.empty() : Optional.of(v);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
