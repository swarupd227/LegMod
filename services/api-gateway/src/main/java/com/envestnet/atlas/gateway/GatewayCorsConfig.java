package com.envestnet.atlas.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Single CORS configuration that covers BOTH the
 * {@code RouteLocator}-proxied routes and the locally-handled
 * {@code @RestController} endpoints (e.g. {@code /api/v1/me},
 * {@code /api/v1/auth/config}) plus {@code /actuator/*}.
 *
 * <p>Why this exists: Spring Cloud Gateway's {@code globalcors} YAML
 * config only attaches CORS to the gateway's filter chain, which means
 * locally-handled controllers inside the same JVM never emit
 * {@code Access-Control-Allow-Origin}. A real browser at
 * {@code http://localhost:3000} CORS-preflights every cross-origin
 * fetch — without this filter, those preflights fail and the SPA
 * sees "Failed to fetch" on every request. We hit this exact bug
 * during the customer-demo recording and worked around it with
 * {@code --disable-web-security} in the Playwright spec; this is the
 * production fix.</p>
 *
 * <p>A {@link CorsWebFilter} runs before route matching and applies
 * uniformly, so it covers every path on this server.</p>
 */
@Configuration
public class GatewayCorsConfig {

    @Bean
    public CorsWebFilter corsWebFilter(
            @Value("${atlas.cors.allowed-origins:http://localhost:3000,http://localhost:4173,http://127.0.0.1:3000}")
            String allowedOriginsCsv) {
        CorsConfiguration config = new CorsConfiguration();
        // Customers running auth-real point this at their SPA host via
        // ATLAS_CORS_ALLOWED_ORIGINS — comma-separated. The defaults
        // cover the dev (vite dev), vite-preview (E2E), and 127.0.0.1
        // (some Playwright versions normalize localhost) cases.
        List<String> origins = Arrays.stream(allowedOriginsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
        config.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE,
                HttpHeaders.ACCEPT,
                "X-Atlas-Request-Id",
                "Idempotency-Key"));
        config.setExposedHeaders(List.of(
                "X-Atlas-Request-Id",
                "X-Atlas-Trace-Id"));
        config.setAllowCredentials(true);
        // Cache preflights for an hour — the SPA's API surface doesn't
        // change shape across requests, so re-preflighting is pure waste.
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
