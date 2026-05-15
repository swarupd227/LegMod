package com.envestnet.atlas.prj.reliability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-unit coverage of {@link IdempotencyFilter}.
 *
 * <p>The "downstream" is a stub FilterChain that records how many times
 * it was invoked + writes a deterministic 2xx response. We assert on:
 * (1) chain invocation count, (2) the response body bytes, (3) the
 * Idempotent-Replay marker header.</p>
 */
class IdempotencyFilterTest {

    private IdempotencyFilter filter;
    private AtomicInteger downstreamCalls;

    @BeforeEach
    void setUp() {
        filter = new IdempotencyFilter(new IdempotencyFilter.InMemoryIdempotencyCache());
        downstreamCalls = new AtomicInteger();
    }

    /**
     * Stub chain that writes "ok-{n}" with a 200 (or whatever status the
     * caller passed) on every invocation. The {@code n} increments per
     * call so we can tell replays from re-executions: a replay returns
     * the body of the call that originally cached.
     */
    private FilterChain echoChain(int status) {
        return (req, res) -> {
            int n = downstreamCalls.incrementAndGet();
            // The filter wraps the response with ContentCachingResponseWrapper
            // before invoking the chain, so we get that here — not the
            // raw MockHttpServletResponse. setStatus is on the
            // HttpServletResponse interface so works for both.
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(status);
            res.setContentType("application/json");
            res.getOutputStream().write(("{\"n\":" + n + "}").getBytes());
        };
    }

    @Test
    void getRequestsPassThroughUntouched() throws ServletException, IOException {
        var req = new MockHttpServletRequest("GET", "/api/v1/projects");
        req.addHeader(IdempotencyFilter.HDR_KEY, "abc-123");
        var res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, echoChain(200));

        assertThat(downstreamCalls).hasValue(1);
        assertThat(res.getHeader(IdempotencyFilter.HDR_REPLAY)).isNull();
    }

    @Test
    void postWithoutIdempotencyKeyAlwaysExecutes() throws ServletException, IOException {
        var chain = echoChain(200);
        for (int i = 0; i < 3; i++) {
            var req = new MockHttpServletRequest("POST", "/api/v1/projects");
            // No Idempotency-Key header.
            filter.doFilterInternal(req, new MockHttpServletResponse(), chain);
        }
        assertThat(downstreamCalls).hasValue(3);
    }

    @Test
    void firstPostWithKeyExecutesAndCaches() throws ServletException, IOException {
        var req = new MockHttpServletRequest("POST", "/api/v1/projects");
        req.addHeader(IdempotencyFilter.HDR_KEY, "abc-123");
        var res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, echoChain(201));

        assertThat(downstreamCalls).hasValue(1);
        assertThat(res.getStatus()).isEqualTo(201);
        assertThat(res.getContentAsString()).isEqualTo("{\"n\":1}");
        assertThat(res.getHeader(IdempotencyFilter.HDR_REPLAY)).isNull();
    }

    @Test
    void replaysCachedResponseOnDuplicateKey() throws ServletException, IOException {
        FilterChain chain = echoChain(200);

        // First call — caches.
        var req1 = new MockHttpServletRequest("POST", "/api/v1/projects");
        req1.addHeader(IdempotencyFilter.HDR_KEY, "dup-1");
        var res1 = new MockHttpServletResponse();
        filter.doFilterInternal(req1, res1, chain);

        // Second call with the SAME key on the SAME endpoint — replay.
        var req2 = new MockHttpServletRequest("POST", "/api/v1/projects");
        req2.addHeader(IdempotencyFilter.HDR_KEY, "dup-1");
        var res2 = new MockHttpServletResponse();
        filter.doFilterInternal(req2, res2, chain);

        // Downstream chain only invoked once.
        assertThat(downstreamCalls).hasValue(1);
        // Both responses identical.
        assertThat(res2.getStatus()).isEqualTo(res1.getStatus());
        assertThat(res2.getContentAsString()).isEqualTo(res1.getContentAsString());
        // The replay carries the marker header so callers / log queries
        // can tell.
        assertThat(res2.getHeader(IdempotencyFilter.HDR_REPLAY)).isEqualTo("true");
        assertThat(res1.getHeader(IdempotencyFilter.HDR_REPLAY)).isNull();
    }

    @Test
    void differentKeyOnSameEndpointReExecutes() throws ServletException, IOException {
        FilterChain chain = echoChain(200);

        var req1 = new MockHttpServletRequest("POST", "/api/v1/projects");
        req1.addHeader(IdempotencyFilter.HDR_KEY, "key-A");
        filter.doFilterInternal(req1, new MockHttpServletResponse(), chain);

        var req2 = new MockHttpServletRequest("POST", "/api/v1/projects");
        req2.addHeader(IdempotencyFilter.HDR_KEY, "key-B");
        filter.doFilterInternal(req2, new MockHttpServletResponse(), chain);

        assertThat(downstreamCalls).hasValue(2);
    }

    @Test
    void sameKeyOnDifferentEndpointReExecutes() throws ServletException, IOException {
        FilterChain chain = echoChain(200);

        var req1 = new MockHttpServletRequest("POST", "/api/v1/projects");
        req1.addHeader(IdempotencyFilter.HDR_KEY, "shared-key");
        filter.doFilterInternal(req1, new MockHttpServletResponse(), chain);

        var req2 = new MockHttpServletRequest(
                "POST", "/api/v1/projects/abc/stages/recipes/finalize");
        req2.addHeader(IdempotencyFilter.HDR_KEY, "shared-key");
        filter.doFilterInternal(req2, new MockHttpServletResponse(), chain);

        // Same key but different scoped (METHOD + URI) → cache miss for the second.
        assertThat(downstreamCalls).hasValue(2);
    }

    @Test
    void sameKeyOnDifferentMethodReExecutes() throws ServletException, IOException {
        FilterChain chain = echoChain(200);

        var post = new MockHttpServletRequest("POST", "/api/v1/projects");
        post.addHeader(IdempotencyFilter.HDR_KEY, "k");
        filter.doFilterInternal(post, new MockHttpServletResponse(), chain);

        var put = new MockHttpServletRequest("PUT", "/api/v1/projects");
        put.addHeader(IdempotencyFilter.HDR_KEY, "k");
        filter.doFilterInternal(put, new MockHttpServletResponse(), chain);

        assertThat(downstreamCalls).hasValue(2);
    }

    @Test
    void nonSuccessResponsesAreNotCached() throws ServletException, IOException {
        FilterChain failing = echoChain(500);

        var req1 = new MockHttpServletRequest("POST", "/api/v1/projects");
        req1.addHeader(IdempotencyFilter.HDR_KEY, "fail");
        filter.doFilterInternal(req1, new MockHttpServletResponse(), failing);

        // Same key, second call — should re-execute because the previous
        // 500 wasn't cached. 5xx is typically transient; replaying it
        // would deny the caller's chance to retry.
        var req2 = new MockHttpServletRequest("POST", "/api/v1/projects");
        req2.addHeader(IdempotencyFilter.HDR_KEY, "fail");
        filter.doFilterInternal(req2, new MockHttpServletResponse(), failing);

        assertThat(downstreamCalls).hasValue(2);
    }

    @Test
    void clientErrorsAreAlsoNotCached() throws ServletException, IOException {
        FilterChain bad = echoChain(400);

        var req1 = new MockHttpServletRequest("POST", "/api/v1/projects");
        req1.addHeader(IdempotencyFilter.HDR_KEY, "bad");
        filter.doFilterInternal(req1, new MockHttpServletResponse(), bad);

        // 4xx not cached either. The caller can fix the request and
        // resubmit with the same key without confusion.
        var req2 = new MockHttpServletRequest("POST", "/api/v1/projects");
        req2.addHeader(IdempotencyFilter.HDR_KEY, "bad");
        filter.doFilterInternal(req2, new MockHttpServletResponse(), bad);

        assertThat(downstreamCalls).hasValue(2);
    }

    @Test
    void blankIdempotencyKeyIsTreatedAsAbsent() throws ServletException, IOException {
        FilterChain chain = echoChain(200);

        for (String key : new String[]{ "", "   " }) {
            var req = new MockHttpServletRequest("POST", "/api/v1/projects");
            req.addHeader(IdempotencyFilter.HDR_KEY, key);
            filter.doFilterInternal(req, new MockHttpServletResponse(), chain);
        }

        assertThat(downstreamCalls).hasValue(2);
    }
}
