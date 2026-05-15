package com.envestnet.atlas.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/**
 * Wiring for Spring Cloud Gateway's request-rate-limiter filter.
 *
 * <p>Two beans:
 * <ul>
 *   <li>{@link #userKeyResolver} — turns each authenticated request
 *       into a per-user rate-limit key. Falls back to the client IP
 *       for unauthenticated traffic (the only routes that should be
 *       hitting the rate limiter without auth are /api/v1/auth/* and
 *       /actuator/**, both small).</li>
 *   <li>{@link #defaultRedisRateLimiter} — token-bucket policy keyed
 *       on the resolved user. Replenish + burst are configurable via
 *       atlas.ratelimit.* properties so customers can tune per
 *       environment without code changes.</li>
 * </ul>
 *
 * <p>The filter itself is attached in {@code GatewayApplication.routes()}
 * — see the {@code .filters(...)} block on each route. Routes that
 * should bypass rate limiting (auth bootstrap, actuator) simply omit
 * the filter.</p>
 */
@Configuration
public class RateLimitConfig {

    public static final String KEY_PREFIX_USER = "user:";
    public static final String KEY_PREFIX_IP   = "ip:";

    /**
     * Per-user rate-limit key. The JWT's subject is the stable identifier
     * across SPA reloads, so two browser sessions for the same user
     * share a bucket — exactly what we want for abuse prevention.
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange ->
                ReactiveSecurityContextHolder.getContext()
                        .map(SecurityContext::getAuthentication)
                        .filter(a -> a instanceof JwtAuthenticationToken)
                        .cast(JwtAuthenticationToken.class)
                        .map(a -> KEY_PREFIX_USER + a.getToken().getSubject())
                        // Fallback: client IP. Useful for /api/v1/auth/* and
                        // any pre-auth probing. X-Forwarded-For is honored
                        // when an upstream LB sets it; falls back to remote
                        // address when not.
                        .switchIfEmpty(Mono.defer(() -> {
                            String ip = headerOrAddress(exchange);
                            return Mono.just(KEY_PREFIX_IP + ip);
                        }));
    }

    private static String headerOrAddress(org.springframework.web.server.ServerWebExchange exchange) {
        String fwd = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            // First entry is the original client (per the de-facto convention).
            int comma = fwd.indexOf(',');
            return (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        var addr = exchange.getRequest().getRemoteAddress();
        return addr == null ? "unknown" : addr.getAddress().getHostAddress();
    }

    /**
     * Default rate-limiter bean. Spring Cloud Gateway's RequestRateLimiter
     * filter picks this up by name. The defaults (10 req/s replenish, 20
     * burst) are deliberately conservative — a real user clicking around
     * the SPA peaks at ~2-3 req/s; 10/s with a 20-token burst gives plenty
     * of headroom while still stopping abuse.
     *
     * <p>Override per environment with:
     * <pre>
     *   atlas.ratelimit.replenish: 50
     *   atlas.ratelimit.burst:    100
     * </pre>
     *
     * <p>Per-route overrides happen at the filter declaration site (in
     * {@code GatewayApplication.routes()}); this bean is the cluster-wide
     * default.
     */
    @Bean
    public RedisRateLimiter defaultRedisRateLimiter(
            @org.springframework.beans.factory.annotation.Value("${atlas.ratelimit.replenish:10}") int replenish,
            @org.springframework.beans.factory.annotation.Value("${atlas.ratelimit.burst:20}") int burst,
            @org.springframework.beans.factory.annotation.Value("${atlas.ratelimit.requested-tokens:1}") int tokens) {
        return new RedisRateLimiter(replenish, burst, tokens);
    }
}
