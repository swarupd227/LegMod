package com.envestnet.atlas.prj.logging;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-unit coverage of {@link RequestContextFilter}. Confirms:
 *  • the filter populates the MDC with the gateway-asserted identity
 *    + request id and project id (when the path is project-scoped);
 *  • the MDC is cleared after the chain completes;
 *  • the response carries the correlation id back to the caller.
 */
class RequestContextFilterTest {

    @AfterEach
    void clearMdc() { MDC.clear(); }

    /**
     * Captures whatever was in the MDC at the moment the chain ran. We
     * snapshot it so the assertion can run after the filter has
     * tear-down-cleared MDC (which is the desired production behaviour).
     */
    private static class CapturingChain implements FilterChain {
        Map<String, String> snapshot;
        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response) {
            snapshot = MDC.getCopyOfContextMap() == null
                    ? new HashMap<>()
                    : new HashMap<>(MDC.getCopyOfContextMap());
        }
    }

    @Test
    void populatesMdcFromGatewayHeadersAndEchoesRequestIdInResponse() throws Exception {
        var req = new MockHttpServletRequest("POST", "/api/v1/projects/11111111-2222-3333-4444-555555555555/stages/recipes/finalize");
        req.addHeader(RequestContextFilter.HDR_REQUEST_ID, "req-abc-123");
        req.addHeader(RequestContextFilter.HDR_USER_EMAIL, "alice@envestnet.local");
        req.addHeader(RequestContextFilter.HDR_USER_ROLES, "ENGINEER,TECH_LEAD");
        var res = new MockHttpServletResponse();
        var chain = new CapturingChain();

        new RequestContextFilter().doFilterInternal(req, res, chain);

        assertThat(chain.snapshot).containsEntry(RequestContextFilter.MDC_REQUEST_ID, "req-abc-123");
        assertThat(chain.snapshot).containsEntry(RequestContextFilter.MDC_USER_EMAIL, "alice@envestnet.local");
        assertThat(chain.snapshot).containsEntry(RequestContextFilter.MDC_USER_ROLES, "ENGINEER,TECH_LEAD");
        assertThat(chain.snapshot).containsEntry(RequestContextFilter.MDC_PROJECT_ID, "11111111-2222-3333-4444-555555555555");

        // Echoed back in the response.
        assertThat(res.getHeader(RequestContextFilter.HDR_REQUEST_ID)).isEqualTo("req-abc-123");
    }

    @Test
    void mdcIsClearedAfterTheChainCompletes() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/me");
        req.addHeader(RequestContextFilter.HDR_REQUEST_ID, "x");
        req.addHeader(RequestContextFilter.HDR_USER_EMAIL, "alice@envestnet.local");

        new RequestContextFilter().doFilterInternal(req, new MockHttpServletResponse(), new CapturingChain());

        // After the filter returns, no keys should be left in the MDC —
        // otherwise we'd leak context onto the next request that lands
        // on the same worker thread.
        assertThat(MDC.get(RequestContextFilter.MDC_REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestContextFilter.MDC_USER_EMAIL)).isNull();
        assertThat(MDC.get(RequestContextFilter.MDC_PROJECT_ID)).isNull();
    }

    @Test
    void generatesRequestIdWhenHeaderIsAbsent() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/workspaces");
        var res = new MockHttpServletResponse();
        var chain = new CapturingChain();

        new RequestContextFilter().doFilterInternal(req, res, chain);

        // Some non-empty value is generated and surfaced via MDC + response.
        String generated = chain.snapshot.get(RequestContextFilter.MDC_REQUEST_ID);
        assertThat(generated).isNotBlank();
        // Looks like a UUID — 36 chars with dashes at the right spots.
        assertThat(generated).matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
        assertThat(res.getHeader(RequestContextFilter.HDR_REQUEST_ID)).isEqualTo(generated);
    }

    @Test
    void blankRequestIdHeaderIsTreatedAsAbsent() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/workspaces");
        req.addHeader(RequestContextFilter.HDR_REQUEST_ID, "   ");
        var chain = new CapturingChain();

        new RequestContextFilter().doFilterInternal(req, new MockHttpServletResponse(), chain);

        // Falls through to the generated path — no whitespace MDC values.
        String reqId = chain.snapshot.get(RequestContextFilter.MDC_REQUEST_ID);
        assertThat(reqId).isNotBlank();
        assertThat(reqId.trim()).isEqualTo(reqId);
    }

    @Test
    void omitsProjectIdWhenPathIsNotProjectScoped() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/workspaces");
        var chain = new CapturingChain();

        new RequestContextFilter().doFilterInternal(req, new MockHttpServletResponse(), chain);

        assertThat(chain.snapshot).doesNotContainKey(RequestContextFilter.MDC_PROJECT_ID);
    }

    @Test
    void omitsUserKeysWhenGatewayHeadersAreAbsent() throws Exception {
        var req = new MockHttpServletRequest("GET", "/api/v1/auth/config");
        // No X-Atlas-User-* headers — public endpoint without auth.
        var chain = new CapturingChain();

        new RequestContextFilter().doFilterInternal(req, new MockHttpServletResponse(), chain);

        assertThat(chain.snapshot).doesNotContainKey(RequestContextFilter.MDC_USER_EMAIL);
        assertThat(chain.snapshot).doesNotContainKey(RequestContextFilter.MDC_USER_ROLES);
        // Request id is always present, identity isn't.
        assertThat(chain.snapshot).containsKey(RequestContextFilter.MDC_REQUEST_ID);
    }

    @Test
    void doesNotMatchUuidsThatAreNotInProjectsPath() throws Exception {
        // Looks like a uuid but isn't preceded by /projects/ — must NOT
        // be captured as projectId.
        var req = new MockHttpServletRequest("GET",
                "/api/v1/workspaces/11111111-2222-3333-4444-555555555555/projects");
        var chain = new CapturingChain();

        new RequestContextFilter().doFilterInternal(req, new MockHttpServletResponse(), chain);

        assertThat(chain.snapshot).doesNotContainKey(RequestContextFilter.MDC_PROJECT_ID);
    }
}
