package com.envestnet.atlas.fakeidp;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the SPA's direct POST /token call. The frontend container
 * runs on :3000 (dev) or :4173 (vite preview / E2E). The /token endpoint
 * receives form-urlencoded credentials, so it needs explicit CORS allow
 * from those origins.
 *
 * <p>Discovery and JWKS endpoints don't need CORS — the gateway, not the
 * browser, fetches them.</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry r) {
        r.addMapping("/token")
                .allowedOrigins(
                        "http://localhost:3000",
                        "http://localhost:4173",
                        "http://127.0.0.1:3000",
                        "http://127.0.0.1:4173")
                .allowedMethods("POST", "OPTIONS")
                .allowedHeaders("Content-Type")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
