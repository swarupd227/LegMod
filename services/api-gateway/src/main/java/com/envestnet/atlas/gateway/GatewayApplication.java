package com.envestnet.atlas.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean
    public RouteLocator routes(
            RouteLocatorBuilder builder,
            RedisRateLimiter defaultRedisRateLimiter,
            KeyResolver userKeyResolver,
            @Value("${PRJ_SERVICE_URL:http://prj-service:8081}") String prjUrl,
            @Value("${LLM_GATEWAY_URL:http://llm-gateway:8082}") String llmUrl) {
        // Apply the per-user rate limiter to the routes that proxy
        // downstream services. /api/v1/auth/** and /actuator/** are
        // NOT routed here (they're handled locally by the gateway's
        // IdentityController + Spring Boot actuator), so they
        // automatically bypass the limiter — which is what we want
        // for pre-auth bootstrap traffic.
        return builder.routes()
                .route("prj", r -> r.path("/api/v1/workspaces/**", "/api/v1/projects/**")
                        .filters(f -> f.requestRateLimiter(c -> c
                                .setRateLimiter(defaultRedisRateLimiter)
                                .setKeyResolver(userKeyResolver)))
                        .uri(prjUrl))
                .route("llm", r -> r.path("/api/v1/llm/**")
                        .filters(f -> f.requestRateLimiter(c -> c
                                .setRateLimiter(defaultRedisRateLimiter)
                                .setKeyResolver(userKeyResolver)))
                        .uri(llmUrl))
                .build();
    }

    /**
     * Derives identity from the validated JWT in the security context — no
     * more {@code DEV_USER_EMAIL} env var. The frontend reads this once
     * after login to populate the chrome with the signed-in user.
     *
     * <p>The {@code demo} flag mirrors the JWT's {@code atlas_demo} claim
     * (set by fake-idp, never set by real OIDC), driving the
     * "DEMO IDENTITY" banner in the chrome.</p>
     */
    @RestController
    static class IdentityController {

        @Value("${atlas.auth.mode:sim}")            private String authMode;
        @Value("${atlas.auth.login-url:}")          private String loginUrl;
        @Value("${atlas.auth.issuer:}")             private String issuer;
        @Value("${atlas.auth.audience:atlas-spa}")  private String audience;
        @Value("${atlas.auth.end-session-url:}")    private String endSessionUrl;
        @Value("${atlas.observability.trace-ui-url:}") private String traceUiUrl;
        /**
         * When {@code false}, the {@code /api/v1/me} response returns
         * {@code demo: false} even if the JWT carries the {@code atlas_demo}
         * claim. The SPA's chrome uses this to gate the "Demo identity"
         * pill — customers running their own auth-real OIDC never see it
         * because the claim is absent, but demos that show the platform
         * via fake-idp can suppress the pill for a customer-facing recording
         * by setting {@code ATLAS_SHOW_DEMO_BADGE=false}.
         */
        @Value("${atlas.ui.show-demo-badge:true}")  private boolean showDemoBadge;

        /**
         * Unauthenticated config probe the SPA hits on first load to learn
         * (a) where to redirect for login, (b) whether to render the
         * "DEMO IDENTITY" banner, (c) where to send users on Sign-out so
         * they're logged out at the IdP too (RP-initiated logout per
         * OIDC). Lives under /api/v1/auth/* which is permitAll'd in
         * {@link SecurityConfig}.
         */
        @GetMapping("/api/v1/auth/config")
        public Mono<Map<String, Object>> authConfig() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode",          authMode);
            body.put("loginUrl",      loginUrl);
            body.put("issuer",        issuer);
            body.put("clientId",      "atlas-spa");
            body.put("audience",      audience);
            body.put("endSessionUrl", endSessionUrl);
            // Where the SPA should send users when they click "View trace"
            // on an audit entry. Empty when no tracing UI is provisioned.
            body.put("traceUiUrl",    traceUiUrl);
            return Mono.just(body);
        }

        @GetMapping("/api/v1/me")
        public Mono<Map<String, Object>> me(Authentication auth) {
            // Authentication is non-null here because /api/v1/me is gated
            // by the security filter chain (`authenticated()`).
            JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) auth;
            Jwt jwt = jwtAuth.getToken();

            String email = stringClaim(jwt, "email").orElse(jwt.getSubject());
            String name  = stringClaim(jwt, "name").orElse(email);
            List<String> roles = jwt.getClaim("roles") instanceof List<?> l
                    ? l.stream().map(Object::toString).toList()
                    : jwtAuth.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority)
                            .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                            .toList();
            boolean demo = Boolean.TRUE.equals(jwt.getClaim("atlas_demo")) && showDemoBadge;

            // Use LinkedHashMap so the keys land in a stable order in the
            // serialized JSON — pleasant for debugging and snapshot-testing.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("email",  email);
            body.put("name",   name);
            body.put("role",   roles.isEmpty() ? "engineer" : roles.get(0).toLowerCase());
            body.put("roles",  roles);
            body.put("demo",   demo);
            return Mono.just(body);
        }

        private static Optional<String> stringClaim(Jwt jwt, String name) {
            Object v = jwt.getClaim(name);
            return v == null ? Optional.empty() : Optional.of(v.toString());
        }
    }
}
