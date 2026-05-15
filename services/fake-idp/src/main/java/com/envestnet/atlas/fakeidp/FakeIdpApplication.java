package com.envestnet.atlas.fakeidp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.List;

/**
 * Atlas fake-IdP — a local OIDC issuer for demos and CI.
 *
 * <p><b>This service is NEVER deployed in production.</b> It signs JWTs
 * using a static keypair generated at startup; anyone with access to the
 * JWKS endpoint can verify them, and anyone with access to the running
 * service can mint tokens for any persona.</p>
 *
 * <p>The architecture deliberately mirrors a real OIDC provider so the
 * gateway's auth code, frontend's login flow, and downstream identity
 * propagation are exercised in demo and production identically. Only the
 * token issuer changes between profiles.</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(FakeIdpApplication.Settings.class)
public class FakeIdpApplication {

    public static void main(String[] args) {
        SpringApplication.run(FakeIdpApplication.class, args);
    }

    @ConfigurationProperties("fake-idp")
    public record Settings(
            String issuer,
            long accessTokenTtlSeconds,
            List<Persona> personas
    ) {
        public record Persona(
                String id,
                String name,
                String title,
                List<String> roles
        ) {}
    }
}
