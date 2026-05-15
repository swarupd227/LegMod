package com.envestnet.atlas.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-unit coverage of the rate-limit key resolution policy. No
 * Spring context, no Redis — just the {@link KeyResolver} bean from
 * {@link RateLimitConfig}.
 *
 * <p>The resolver is the rate-limiter's bucket key, so its correctness
 * controls who shares a budget. We pin four behaviors:
 *  • Authenticated requests key on {@code user:<sub>}.
 *  • Unauthenticated requests fall back to {@code ip:<remote>}.
 *  • {@code X-Forwarded-For} (single hop) is honored.
 *  • Multi-hop {@code X-Forwarded-For} takes the first entry (the
 *    original client per the de-facto convention).</p>
 */
class RateLimitConfigTest {

    private final KeyResolver resolver = new RateLimitConfig().userKeyResolver();

    private static Jwt jwtFor(String subject) {
        return new Jwt(
                "fake.token.value",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of("sub", subject, "email", subject));
    }

    @Test
    void authenticatedRequestKeysOnTheJwtSubject() {
        var req = MockServerHttpRequest.get("/api/v1/projects").build();
        var exchange = MockServerWebExchange.from(req);

        var auth = new JwtAuthenticationToken(jwtFor("alice@envestnet.local"));
        Mono<String> key = resolver.resolve(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                        Mono.just(new SecurityContextImpl(auth))));

        StepVerifier.create(key)
                .expectNext("user:alice@envestnet.local")
                .verifyComplete();
    }

    @Test
    void unauthenticatedRequestFallsBackToRemoteAddress() {
        var req = MockServerHttpRequest.get("/api/v1/auth/config")
                .remoteAddress(new java.net.InetSocketAddress("203.0.113.7", 50000))
                .build();
        var exchange = MockServerWebExchange.from(req);

        Mono<String> key = resolver.resolve(exchange);

        StepVerifier.create(key)
                .expectNext("ip:203.0.113.7")
                .verifyComplete();
    }

    @Test
    void xForwardedForHeaderOverridesRemoteAddress() {
        // Simulates the typical setup where a layer-7 LB sets
        // X-Forwarded-For to the original client IP. The gateway sees
        // the LB as the remoteAddress but should honor the header.
        HttpHeaders h = new HttpHeaders();
        h.add("X-Forwarded-For", "198.51.100.42");
        var req = MockServerHttpRequest.get("/api/v1/projects")
                .headers(h)
                .remoteAddress(new java.net.InetSocketAddress("10.0.0.1", 50000))
                .build();
        var exchange = MockServerWebExchange.from(req);

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("ip:198.51.100.42")
                .verifyComplete();
    }

    @Test
    void multiHopXForwardedForTakesTheFirstEntry() {
        // The de-facto convention: X-Forwarded-For chains hops with
        // commas; the original client is FIRST. (Spring Cloud Gateway,
        // CloudFront, AWS ALB, GCLB, nginx — all converge here.)
        HttpHeaders h = new HttpHeaders();
        h.add("X-Forwarded-For", "203.0.113.5, 198.51.100.10, 10.0.0.1");
        var req = MockServerHttpRequest.get("/api/v1/projects")
                .headers(h)
                .build();
        var exchange = MockServerWebExchange.from(req);

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("ip:203.0.113.5")
                .verifyComplete();
    }

    @Test
    void resolverHandlesMissingRemoteAddressGracefully() {
        // No remoteAddress, no X-Forwarded-For. Resolver returns
        // "ip:unknown" rather than crashing — the rate-limiter then
        // groups all such traffic into one bucket, which is fine
        // because it's a vanishingly rare situation (test harness or
        // misconfigured proxy).
        var req = MockServerHttpRequest.get("/api/v1/auth/config").build();
        var exchange = MockServerWebExchange.from(req);

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("ip:unknown")
                .verifyComplete();
    }

    @Test
    void nonJwtAuthenticationFallsBackToIp() {
        // If some custom Authentication ends up in the context (rare —
        // the gateway only knows JWT auth today), we shouldn't crash;
        // we should fall through to IP-keyed buckets.
        var req = MockServerHttpRequest.get("/api/v1/projects")
                .remoteAddress(new java.net.InetSocketAddress("203.0.113.99", 0))
                .build();
        var exchange = MockServerWebExchange.from(req);

        var auth = new TestingAuthenticationToken("alice", "pass", "ROLE_USER");
        auth.setAuthenticated(true);
        Mono<String> key = resolver.resolve(exchange)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                        Mono.just(new SecurityContextImpl(auth))));

        StepVerifier.create(key)
                .expectNext("ip:203.0.113.99")
                .verifyComplete();
    }
}
