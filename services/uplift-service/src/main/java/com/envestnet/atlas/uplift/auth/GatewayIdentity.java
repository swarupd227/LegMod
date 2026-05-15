package com.envestnet.atlas.uplift.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

/**
 * Reads the gateway-asserted identity headers from the inbound request.
 * See {@code services/prj-service/.../auth/GatewayIdentity.java} for the
 * canonical commentary; this is a deliberate copy because creating a
 * shared Maven module for ~30 lines of code is not worth the build-graph
 * complexity in the local-Docker setup.
 *
 * <p>The headers are populated by api-gateway's
 * {@code IdentityForwardingFilter}; client-supplied values are stripped
 * BEFORE the gateway re-stamps them, so anything reaching this method
 * is authoritative.</p>
 */
public final class GatewayIdentity {

    public static final String HDR_EMAIL = "X-Atlas-User-Email";
    public static final String HDR_NAME  = "X-Atlas-User-Name";
    public static final String HDR_ROLES = "X-Atlas-User-Roles";

    private GatewayIdentity() {}

    public static Optional<String> currentEmail() { return header(HDR_EMAIL); }
    public static Optional<String> currentName()  { return header(HDR_NAME); }

    public static List<String> currentRoles() {
        return header(HDR_ROLES)
                .map(s -> List.of(s.split(",")))
                .orElse(List.of());
    }

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
