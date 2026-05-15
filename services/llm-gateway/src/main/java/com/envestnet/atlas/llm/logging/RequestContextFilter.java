package com.envestnet.atlas.llm.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Populates the SLF4J MDC for the duration of each HTTP request so every
 * log line emitted while handling that request is automatically tagged
 * with correlation context.
 *
 * <p>Keys set in the MDC:
 * <ul>
 *   <li>{@code requestId} — read from {@value #HDR_REQUEST_ID} or
 *       generated fresh per request. Echoed back in the response so the
 *       caller can correlate.</li>
 *   <li>{@code userEmail} — gateway-asserted via
 *       {@value #HDR_USER_EMAIL}.</li>
 *   <li>{@code userRoles} — gateway-asserted comma-separated roles
 *       ({@value #HDR_USER_ROLES}).</li>
 *   <li>{@code projectId} — extracted from request paths shaped like
 *       {@code /api/v1/projects/&lt;uuid&gt;/...} or
 *       {@code /internal/&lt;feature&gt;/projects/&lt;uuid&gt;/...},
 *       since project-scoped traffic dominates the platform.</li>
 * </ul>
 *
 * <p>The MDC is always cleared in a {@code finally} block so the keys
 * never leak across requests on a reused worker thread.</p>
 *
 * <p>Note: this class is duplicated across services because they don't
 * share a Maven module. The 30 LOC of duplication isn't worth the
 * build-graph complexity of a shared library.</p>
 */
@Component("atlasRequestContextFilter")
public class RequestContextFilter extends OncePerRequestFilter {

    public static final String HDR_REQUEST_ID   = "X-Atlas-Request-Id";
    public static final String HDR_TRACE_ID     = "X-Atlas-Trace-Id";
    public static final String HDR_USER_EMAIL   = "X-Atlas-User-Email";
    public static final String HDR_USER_ROLES   = "X-Atlas-User-Roles";
    /**
     * Workspace context — set by callers after the prj-service workspace
     * authorization check has resolved the project's workspace. The
     * gateway logs it for per-tenant log partitioning and {@code
     * LlmController} reads it to tag the {@code atlas_llm_cost_usd_total}
     * counter. See docs/multi-tenancy.md.
     */
    public static final String HDR_WORKSPACE_ID = "X-Atlas-Workspace-Id";
    /**
     * Optional project context for callers that want to attribute a
     * specific LLM call to a project even when the URL doesn't carry
     * the projectId. Falls back to the URL-path extraction below.
     */
    public static final String HDR_PROJECT_ID   = "X-Atlas-Project-Id";

    public static final String MDC_REQUEST_ID   = "requestId";
    /** Populated by Micrometer Tracing once a span is active for the request. */
    public static final String MDC_TRACE_ID     = "traceId";
    public static final String MDC_USER_EMAIL   = "userEmail";
    public static final String MDC_USER_ROLES   = "userRoles";
    public static final String MDC_PROJECT_ID   = "projectId";
    public static final String MDC_WORKSPACE_ID = "workspaceId";

    /** Match a `/projects/<uuid>/` segment anywhere in the path. */
    private static final Pattern PROJECT_IN_PATH =
            Pattern.compile("/projects/([0-9a-fA-F-]{36})(?:/|$)");

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                     HttpServletResponse res,
                                     FilterChain chain)
            throws ServletException, IOException {
        String reqId = req.getHeader(HDR_REQUEST_ID);
        if (reqId == null || reqId.isBlank()) reqId = UUID.randomUUID().toString();

        try {
            MDC.put(MDC_REQUEST_ID, reqId);
            putIfPresent(MDC_USER_EMAIL,   req.getHeader(HDR_USER_EMAIL));
            putIfPresent(MDC_USER_ROLES,   req.getHeader(HDR_USER_ROLES));
            putIfPresent(MDC_WORKSPACE_ID, req.getHeader(HDR_WORKSPACE_ID));
            // Header-supplied projectId beats URL extraction — callers
            // know the project even when their URL doesn't carry it.
            putIfPresent(MDC_PROJECT_ID,   req.getHeader(HDR_PROJECT_ID));

            String uri = req.getRequestURI();
            if (uri != null && MDC.get(MDC_PROJECT_ID) == null) {
                var m = PROJECT_IN_PATH.matcher(uri);
                if (m.find()) MDC.put(MDC_PROJECT_ID, m.group(1));
            }

            res.setHeader(HDR_REQUEST_ID, reqId);
            chain.doFilter(req, res);

            // After the filter chain has run, Micrometer's tracing
            // instrumentation has populated the MDC with traceId. Echo
            // it back on the response so the SPA / curl can deep-link
            // to Jaeger. We set it here (post-chain) rather than at
            // entry because the span may not exist before the
            // observation handler runs.
            String traceId = MDC.get(MDC_TRACE_ID);
            if (traceId != null && !traceId.isBlank()) {
                res.setHeader(HDR_TRACE_ID, traceId);
            }
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_USER_EMAIL);
            MDC.remove(MDC_USER_ROLES);
            MDC.remove(MDC_PROJECT_ID);
            MDC.remove(MDC_WORKSPACE_ID);
            // Don't touch MDC_TRACE_ID — Micrometer owns its lifecycle.
        }
    }

    private static void putIfPresent(String key, String value) {
        if (value != null && !value.isBlank()) MDC.put(key, value);
    }
}
