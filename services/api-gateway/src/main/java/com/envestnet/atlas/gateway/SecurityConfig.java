package com.envestnet.atlas.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Reactive Spring Security filter chain for the gateway.
 *
 * <p>Auth model: every request to {@code /api/v1/**} except for the
 * {@code /api/v1/me} probe must carry a valid bearer JWT. The JWT is
 * validated against the configured issuer's JWKS — see
 * {@code application.yml} for the {@code auth-sim} (fake-idp) and
 * {@code auth-real} (customer OIDC) profile bindings.</p>
 *
 * <p>Roles in the JWT's {@code roles} claim are mapped to Spring authorities
 * with the conventional {@code ROLE_} prefix so {@code hasRole('ENGINEER')}
 * checks at downstream controllers behave as expected.</p>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * JSON-pointer-style path to the JWT claim that holds the user's
     * roles array. Defaults to {@code roles} (Atlas's fake-idp + Entra
     * ID-with-app-roles). Customers using Keycloak set this to
     * {@code realm_access.roles}; Okta typically uses {@code groups}.
     */
    @Value("${atlas.auth.roles-claim:roles}")
    private String rolesClaimPath;

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
            // Stateless API — no CSRF tokens needed; CORS is configured in
            // application.yml at the gateway level (one place, one origin).
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(c -> {})
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .authorizeExchange(ex -> ex
                // Public probes — actuator for ops + auth/* for the SPA's
                // pre-login bootstrap (it needs to know where to redirect
                // BEFORE it has a token).
                .pathMatchers("/actuator/**").permitAll()
                .pathMatchers("/api/v1/auth/**").permitAll()
                // OpenAPI / Swagger UI live at well-known paths and need
                // to be reachable without a JWT so SDK consumers can
                // discover the contract. In production, lock these down
                // by removing this rule + setting springdoc.api-docs.enabled=false.
                .pathMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                // ---------- Gate-finalize endpoints: TECH_LEAD only. ----------
                // Finalizing a gate moves the project's currentStage cursor
                // forward and is a sign-off moment, so we restrict to
                // TECH_LEAD (and any role that has TECH_LEAD as a subset,
                // i.e. ADMIN). Engineers can run/decide/edit at will but
                // can't promote the gate.
                .pathMatchers(HttpMethod.POST,
                    "/api/v1/projects/*/stages/recipes/finalize",
                    "/api/v1/projects/*/stages/strangler/finalize",
                    "/api/v1/projects/*/stages/migration/finalize",
                    "/api/v1/projects/*/stages/characterize/finalize",
                    "/api/v1/projects/*/stages/cutover/finalize",
                    "/api/v1/projects/*/stages/capture/finalize",
                    "/api/v1/projects/*/stages/reports/build"
                ).hasRole("TECH_LEAD")

                // Day-to-day work — anything else under /api/v1/** — needs
                // any authenticated user with at least the ENGINEER role.
                // Demo personas all carry ENGINEER; production IdPs that
                // omit it land users in a 403 here, which is the correct
                // signal (the customer needs to provision the role).
                .pathMatchers("/api/v1/**").hasRole("ENGINEER")
                .anyExchange().permitAll()
            )
            .oauth2ResourceServer(o -> o.jwt(jwt -> jwt
                    .jwtAuthenticationConverter(jwtAuthConverter())));
        return http.build();
    }

    /**
     * Maps the configured roles claim (path is {@link #rolesClaimPath})
     * to Spring authorities with the {@code ROLE_} prefix. Demo personas
     * have {@code [ENGINEER]}, {@code [ENGINEER, TECH_LEAD]}, or
     * {@code [ENGINEER, TECH_LEAD, ADMIN]}.
     *
     * <p>For nested paths (e.g. {@code realm_access.roles} on Keycloak)
     * we walk the claims map. Roles can be a JSON array of strings or
     * a comma-separated string — both shapes are accepted.</p>
     */
    private Converter<Jwt, reactor.core.publisher.Mono<AbstractAuthenticationToken>> jwtAuthConverter() {
        ReactiveJwtAuthenticationConverter conv = new ReactiveJwtAuthenticationConverter();
        conv.setJwtGrantedAuthoritiesConverter(jwt -> {
            Object claim = readClaim(jwt.getClaims(), rolesClaimPath);
            Collection<GrantedAuthority> out;
            if (claim instanceof List<?> list) {
                out = list.stream()
                        .map(Object::toString)
                        .map(s -> "ROLE_" + s)
                        .map(SimpleGrantedAuthority::new)
                        .map(a -> (GrantedAuthority) a)
                        .toList();
            } else if (claim instanceof String s && !s.isBlank()) {
                List<GrantedAuthority> auths = new ArrayList<>();
                for (String part : s.split(",")) {
                    String trimmed = part.trim();
                    if (!trimmed.isEmpty()) {
                        auths.add(new SimpleGrantedAuthority("ROLE_" + trimmed));
                    }
                }
                out = auths;
            } else {
                out = Collections.emptyList();
            }
            return Flux.fromIterable(out);
        });
        return conv;
    }

    /**
     * Read a claim by simple name or dotted path through a claims map.
     * Package-private for testing.
     */
    static Object readClaim(Map<String, Object> claims, String path) {
        if (claims == null || path == null || path.isBlank()) return null;
        String[] segments = path.split("\\.");
        Object cursor = claims;
        for (String seg : segments) {
            if (!(cursor instanceof Map<?, ?> m)) return null;
            cursor = m.get(seg);
            if (cursor == null) return null;
        }
        return cursor;
    }
}
