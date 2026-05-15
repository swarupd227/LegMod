package com.envestnet.atlas.prj.auth;

import com.envestnet.atlas.prj.logging.RequestContextFilter;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Propagates the gateway-asserted identity headers from the current
 * inbound request onto every outbound RestTemplate call.
 *
 * <p>prj-service routinely fans out to uplift-service, arch-service,
 * cap-service, etc. in response to a user request. Without this
 * interceptor the gateway's {@code X-Atlas-User-*} headers terminate at
 * prj-service, so downstream services lose the user identity. The
 * interceptor copies the headers onto each outbound request so the
 * identity propagates the full call graph.</p>
 *
 * <p>If we're running outside an HTTP request context (e.g. background
 * task, test), the interceptor is a no-op — outbound traffic just goes
 * out without the headers.</p>
 */
@Component
public class IdentityForwardingInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                         ClientHttpRequestExecution execution) throws IOException {
        // Don't override headers a caller explicitly set.
        if (request.getHeaders().getFirst(GatewayIdentity.HDR_EMAIL) == null) {
            GatewayIdentity.currentEmail().ifPresent(v ->
                    request.getHeaders().add(GatewayIdentity.HDR_EMAIL, v));
        }
        if (request.getHeaders().getFirst(GatewayIdentity.HDR_NAME) == null) {
            GatewayIdentity.currentName().ifPresent(v ->
                    request.getHeaders().add(GatewayIdentity.HDR_NAME, v));
        }
        if (request.getHeaders().getFirst(GatewayIdentity.HDR_ROLES) == null) {
            String roles = String.join(",", GatewayIdentity.currentRoles());
            if (!roles.isEmpty()) request.getHeaders().add(GatewayIdentity.HDR_ROLES, roles);
        }
        // Forward the per-request correlation id so the next hop's MDC
        // also tags its log lines with the same id. Read from the SLF4J
        // MDC (set by RequestContextFilter) rather than the inbound
        // headers directly — the MDC is the authoritative source of
        // "what request am I servicing right now."
        if (request.getHeaders().getFirst(RequestContextFilter.HDR_REQUEST_ID) == null) {
            String reqId = org.slf4j.MDC.get(RequestContextFilter.MDC_REQUEST_ID);
            if (reqId != null && !reqId.isBlank()) {
                request.getHeaders().add(RequestContextFilter.HDR_REQUEST_ID, reqId);
            }
        }
        // Workspace context (Phase 2K). The controller stamps the
        // resolved workspaceId on the request attributes after the
        // membership check passes; we read it from the SLF4J MDC
        // (which the controller also populates) so this works in any
        // thread that inherits the MDC, including the
        // SimpleAsyncTaskExecutor used for some background fan-outs.
        // Downstream services treat this header as authoritative —
        // prj-service has already proven the caller is a member.
        if (request.getHeaders().getFirst(RequestContextFilter.HDR_WORKSPACE_ID) == null) {
            String wsId = org.slf4j.MDC.get(RequestContextFilter.MDC_WORKSPACE_ID);
            if (wsId != null && !wsId.isBlank()) {
                request.getHeaders().add(RequestContextFilter.HDR_WORKSPACE_ID, wsId);
            }
        }
        return execution.execute(request, body);
    }
}
