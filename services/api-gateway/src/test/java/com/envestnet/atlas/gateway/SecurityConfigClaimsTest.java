package com.envestnet.atlas.gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the configurable roles-claim contract added in Phase 2D.4. The
 * test sets {@code atlas.auth.roles-claim=realm_access.roles} (Keycloak
 * convention) and confirms that:
 *
 * <ul>
 *   <li>A JWT with the nested claim is accepted and the user gets the
 *       Spring authorities they expect.</li>
 *   <li>A JWT with the top-level {@code roles} claim (Atlas's default
 *       shape) is rejected — the user lacks the configured authority.</li>
 *   <li>An invalid audience is rejected at the validator before the
 *       role check runs (so we get 401, not 403).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {
                    "spring.profiles.active=auth-sim",
                    "atlas.auth.mode=sim",
                    "atlas.auth.login-url=http://localhost:8093/authorize",
                    "atlas.auth.issuer=http://localhost:8093",
                    "atlas.auth.roles-claim=realm_access.roles"
                })
class SecurityConfigClaimsTest {

    static HttpServer jwksServer;
    static RSAKey rsaJwk;
    static String issuerUri;

    @BeforeAll
    static void startJwks() throws Exception {
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
    static void stopJwks() {
        if (jwksServer != null) jwksServer.stop(0);
    }

    @DynamicPropertySource
    static void overrideJwt(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuerUri);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
              () -> issuerUri + "/.well-known/jwks.json");
        // Explicitly force the audience; the YAML profile sets it too,
        // but DynamicPropertySource leaves Spring Boot's binding of
        // `audiences` ambiguous when other jwt.* keys are dynamic.
        r.add("spring.security.oauth2.resourceserver.jwt.audiences[0]", () -> "atlas-spa");
    }

    @Autowired WebTestClient web;

    @Test
    void keycloakStyleNestedRolesClaimIsAccepted() throws Exception {
        // Mint a Keycloak-shaped JWT: roles live at realm_access.roles
        // rather than the top-level `roles` claim.
        Map<String, Object> realmAccess = Map.of("roles", List.of("ENGINEER", "TECH_LEAD"));
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuerUri)
                .subject("alice@envestnet.local")
                .audience("atlas-spa")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("email", "alice@envestnet.local")
                .claim("name", "Alice")
                .claim("realm_access", realmAccess)
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaJwk.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(rsaJwk));

        // /api/v1/me requires hasRole("ENGINEER"). With roles-claim
        // pointed at realm_access.roles, the Keycloak-shaped JWT works.
        web.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + jwt.serialize())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void topLevelRolesClaimIsIgnoredWhenPathIsNested() throws Exception {
        // Mint a JWT with the OLD shape (roles at top level). With
        // atlas.auth.roles-claim=realm_access.roles, the converter
        // doesn't see this — the user has no authorities → 403.
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuerUri)
                .subject("alice@envestnet.local")
                .audience("atlas-spa")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("email", "alice@envestnet.local")
                .claim("roles", List.of("ENGINEER"))   // wrong path
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaJwk.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(rsaJwk));

        web.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + jwt.serialize())
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void wrongAudienceIsRejectedBeforeRoleCheck() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuerUri)
                .subject("alice@envestnet.local")
                .audience("some-other-tenant")          // wrong audience
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("realm_access", Map.of("roles", List.of("ENGINEER")))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaJwk.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(rsaJwk));

        // Audience validator rejects the JWT entirely → 401, not 403.
        web.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + jwt.serialize())
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
