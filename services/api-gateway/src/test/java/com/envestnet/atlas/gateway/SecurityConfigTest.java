package com.envestnet.atlas.gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.sun.net.httpserver.HttpServer;

/**
 * Boots the gateway with the auth-sim profile and a synthetic JWKS endpoint
 * served by an in-process {@link HttpServer}. Validates:
 *  • unauthenticated endpoints (/actuator/health, /api/v1/auth/config) — 200
 *  • authenticated endpoints (/api/v1/me) — 401 without bearer, 200 with one
 *  • the {@code roles} claim flows into the response body
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {
                    "spring.profiles.active=auth-sim",
                    "atlas.auth.mode=sim",
                    "atlas.auth.login-url=http://localhost:8093/authorize",
                    "atlas.auth.issuer=http://localhost:8093"
                })
class SecurityConfigTest {

    static HttpServer jwksServer;
    static RSAKey rsaJwk;
    static String issuerUri;

    @BeforeAll
    static void startJwksServer() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        rsaJwk = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();

        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuerUri = "http://127.0.0.1:" + jwksServer.getAddress().getPort();
        jwksServer.createContext("/.well-known/jwks.json", ex -> {
            byte[] body = new JWKSet(rsaJwk.toPublicJWK()).toString().getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        jwksServer.start();
    }

    @AfterAll
    static void stopJwksServer() {
        if (jwksServer != null) jwksServer.stop(0);
    }

    @DynamicPropertySource
    static void overrideJwt(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuerUri);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
              () -> issuerUri + "/.well-known/jwks.json");
    }

    @Autowired WebTestClient web;

    @Test
    void healthIsPublic() {
        web.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void authConfigIsPublic() {
        web.get().uri("/api/v1/auth/config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.mode").isEqualTo("sim")
                .jsonPath("$.loginUrl").isEqualTo("http://localhost:8093/authorize");
    }

    @Test
    void meRequiresAuthentication() {
        web.get().uri("/api/v1/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void meReturnsIdentityFromValidJwt() throws Exception {
        String token = mintToken("alice@envestnet.local", "Alice", List.of("ENGINEER"), true);

        web.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email").isEqualTo("alice@envestnet.local")
                .jsonPath("$.name").isEqualTo("Alice")
                .jsonPath("$.role").isEqualTo("engineer")
                .jsonPath("$.roles[0]").isEqualTo("ENGINEER")
                .jsonPath("$.demo").isEqualTo(true);
    }

    @Test
    void invalidJwtIsRejected() {
        web.get().uri("/api/v1/me")
                .header("Authorization", "Bearer not-a-real-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void multipleRolesPropagateThroughTheClaim() throws Exception {
        String token = mintToken("carol@envestnet.local", "Carol",
                                 List.of("ENGINEER", "TECH_LEAD", "ADMIN"), false);

        web.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.roles[0]").isEqualTo("ENGINEER")
                .jsonPath("$.roles[1]").isEqualTo("TECH_LEAD")
                .jsonPath("$.roles[2]").isEqualTo("ADMIN")
                .jsonPath("$.demo").isEqualTo(false);
    }

    private static String mintToken(String email, String name, List<String> roles, boolean demo) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuerUri)
                .subject(email)
                .audience("atlas-spa")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("email", email)
                .claim("name", name)
                .claim("roles", roles)
                .claim("atlas_demo", demo)
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaJwk.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(rsaJwk));
        return jwt.serialize();
    }
}
