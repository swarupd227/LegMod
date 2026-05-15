package com.envestnet.atlas.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stamps every authenticated forwarded request with three headers that
 * downstream services trust as the authoritative identity:
 *
 * <ul>
 *   <li>{@code X-Atlas-User-Email}  — the JWT's {@code email} claim (or
 *       {@code sub} if missing)</li>
 *   <li>{@code X-Atlas-User-Name}   — the JWT's {@code name} claim</li>
 *   <li>{@code X-Atlas-User-Roles}  — comma-separated list of granted
 *       authorities, with the {@code ROLE_} prefix stripped (so a
 *       downstream sees {@code TECH_LEAD,ENGINEER}, not
 *       {@code ROLE_TECH_LEAD,ROLE_ENGINEER})</li>
 * </ul>
 *
 * <p><b>Defense in depth:</b> client-supplied {@code X-Atlas-User-*}
 * headers are stripped from every request before authentication, so a
 * caller can never spoof these. Downstream services that trust the
 * headers can do so unconditionally.</p>
 *
 * <p><b>Network assumption:</b> downstream services in production must
 * not be reachable from the public internet — only via the gateway.
 * Docker Desktop demos expose host ports for convenience; production
 * compose / k8s deployments must keep downstream services on a private
 * network. This is documented in docs/auth.md.</p>
 *
 * <p>Runs <i>after</i> the security filter so the JWT has already been
 * validated and the {@code Authentication} is on the security context.
 * Sits well before the routing filter so headers reach the downstream.</p>
 */
@Component
public class IdentityForwardingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(IdentityForwardingFilter.class);

    public static final String HDR_EMAIL = "X-Atlas-User-Email";
    public static final String HDR_NAME  = "X-Atlas-User-Name";
    public static final String HDR_ROLES = "X-Atlas-User-Roles";

    private static final Set<String> STRIPPED = Set.of(
            HDR_EMAIL.toLowerCase(),
            HDR_NAME.toLowerCase(),
            HDR_ROLES.toLowerCase());

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. Strip any client-sent X-Atlas-User-* headers via a decorator.
        //    `request.mutate().headers(consumer)` hands us the request's
        //    immutable HttpHeaders in some configurations, so we go via a
        //    decorator that re-emits headers with the spoofed ones gone.
        ServerHttpRequest stripped = stripIdentityHeaders(exchange.getRequest());
        ServerWebExchange strippedEx = exchange.mutate().request(stripped).build();

        // 2. If the request is authenticated, layer in the gateway-asserted
        //    identity headers. Anonymous endpoints (/actuator, /api/v1/auth)
        //    won't have a JwtAuthenticationToken — they pass through with
        //    no identity headers, which is the correct behaviour.
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth instanceof JwtAuthenticationToken)
                .cast(JwtAuthenticationToken.class)
                .map(jwtAuth -> withIdentityHeaders(strippedEx, jwtAuth))
                .defaultIfEmpty(strippedEx)
                .flatMap(chain::filter);
    }

    private static ServerHttpRequest stripIdentityHeaders(ServerHttpRequest original) {
        return new ServerHttpRequestDecorator(original) {
            private HttpHeaders cached;
            @Override
            public HttpHeaders getHeaders() {
                if (cached == null) {
                    HttpHeaders out = new HttpHeaders();
                    super.getHeaders().forEach((name, values) -> {
                        if (!STRIPPED.contains(name.toLowerCase())) {
                            out.addAll(name, values);
                        }
                    });
                    cached = HttpHeaders.readOnlyHttpHeaders(out);
                }
                return cached;
            }
        };
    }

    private ServerWebExchange withIdentityHeaders(ServerWebExchange exchange, JwtAuthenticationToken auth) {
        var jwt = auth.getToken();
        String email = stringClaim(jwt::getClaim, "email", jwt.getSubject());
        String name  = stringClaim(jwt::getClaim, "name",  email);
        String roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .collect(Collectors.joining(","));

        // mutate() copies headers into a writable HttpHeaders, so single
        // header() additions are fine — it's only bulk consumer mutation
        // that hits the read-only delegate.
        ServerHttpRequest req = exchange.getRequest().mutate()
                .header(HDR_EMAIL, email)
                .header(HDR_NAME,  name)
                .header(HDR_ROLES, roles)
                .build();
        return exchange.mutate().request(req).build();
    }

    private static String stringClaim(java.util.function.Function<String, Object> claim,
                                       String name, String fallback) {
        Object v = claim.apply(name);
        return v == null ? fallback : v.toString();
    }

    @Override
    public int getOrder() {
        // Run after RemoveHopByHopHeadersFilter (LOWEST_PRECEDENCE - 1) but
        // before NettyRoutingFilter (LOWEST_PRECEDENCE). Concretely, any
        // value < LOWEST_PRECEDENCE works; pick something safely earlier
        // than routing so the headers definitely make it onto the wire.
        return Ordered.LOWEST_PRECEDENCE - 100;
    }
}
