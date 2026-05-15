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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.UUID;

/**
 * Pins the role-based authorization rules added in Phase 2D.2:
 *   - Engineers can hit non-finalize routes (/me, /workspaces, …)
 *   - Engineers get 403 on every gate-finalize endpoint
 *   - Tech leads get past authorization on every finalize endpoint
 *     (the request continues to the routing filter; we don't care
 *     about the upstream response here, only that authorization let
 *     the request through)
 *
 * Authorization is checked via WebTestClient against the running
 * gateway. JWTs are signed by an in-process RSA key whose JWKS is
 * served by an embedded {@link HttpServer}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {
                    "spring.profiles.active=auth-sim",
                    "atlas.auth.mode=sim",
                    "atlas.auth.login-url=http://localhost:8093/authorize",
                    "atlas.auth.issuer=http://localhost:8093"
                })
class FinalizeAuthorizationTest {

    static HttpServer jwksServer;
    static HttpServer prjStub;
    static RSAKey rsaJwk;
    static String issuerUri;
    static String prjUrl;

    @BeforeAll
    static void startServers() throws Exception {
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

        // prj-service stand-in. The gateway's `prj` route forwards
        // /api/v1/projects/** here. Returns a static 200 so the security
        // filter's allow path actually completes within WebTestClient's
        // 5-second blocking read.
        prjStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        prjUrl = "http://127.0.0.1:" + prjStub.getAddress().getPort();
        prjStub.createContext("/", ex -> {
            byte[] body = "{\"ok\":true}".getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        prjStub.start();
    }

    @AfterAll
    static void stopServers() {
        if (jwksServer != null) jwksServer.stop(0);
        if (prjStub != null)    prjStub.stop(0);
    }

    @DynamicPropertySource
    static void overrideJwt(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuerUri);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
              () -> issuerUri + "/.well-known/jwks.json");
        r.add("PRJ_SERVICE_URL", () -> prjUrl);
        r.add("LLM_GATEWAY_URL", () -> prjUrl);
    }

    @Autowired WebTestClient web;

    private static final UUID PID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** Every finalize endpoint that requires TECH_LEAD or higher. */
    private static final String[] FINALIZE_PATHS = {
        "/api/v1/projects/" + PID + "/stages/recipes/finalize",
        "/api/v1/projects/" + PID + "/stages/strangler/finalize",
        "/api/v1/projects/" + PID + "/stages/migration/finalize",
        "/api/v1/projects/" + PID + "/stages/characterize/finalize",
        "/api/v1/projects/" + PID + "/stages/cutover/finalize",
        "/api/v1/projects/" + PID + "/stages/capture/finalize",
        "/api/v1/projects/" + PID + "/stages/reports/build"
    };

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/projects/11111111-1111-1111-1111-111111111111/stages/recipes/finalize",
        "/api/v1/projects/11111111-1111-1111-1111-111111111111/stages/strangler/finalize",
        "/api/v1/projects/11111111-1111-1111-1111-111111111111/stages/migration/finalize",
        "/api/v1/projects/11111111-1111-1111-1111-111111111111/stages/characterize/finalize",
        "/api/v1/projects/11111111-1111-1111-1111-111111111111/stages/cutover/finalize",
        "/api/v1/projects/11111111-1111-1111-1111-111111111111/stages/capture/finalize",
        "/api/v1/projects/11111111-1111-1111-1111-111111111111/stages/reports/build"
    })
    void engineerCannotFinalize(String path) throws Exception {
        String token = mintToken("alice@envestnet.local", List.of("ENGINEER"));
        web.post().uri(path)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isForbidden();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/projects/11111111-1111-1111-1111-111111111111/stages/recipes/finalize",
        "/api/v1/projects/11111111-1111-1111-1111-111111111111/stages/strangler/finalize",
        "/api/v1/projects/11111111-1111-1111-1111-111111111111/stages/migration/finalize",
        "/api/v1/projects/11111111-1111-1111-1111-111111111111/stages/characterize/finalize",
        "/api/v1/projects/11111111-1111-1111-1111-111111111111/stages/cutover/finalize",
        "/api/v1/projects/11111111-1111-1111-1111-111111111111/stages/capture/finalize",
        "/api/v1/projects/11111111-1111-1111-1111-111111111111/stages/reports/build"
    })
    void techLeadIsAllowedThroughAuthorization(String path) throws Exception {
        String token = mintToken("bob@envestnet.local", List.of("ENGINEER", "TECH_LEAD"));
        // The downstream prj-service isn't running in this test, so the
        // request fails at the routing filter (typically 502/504). What we
        // care about is that authorization let it through — i.e. the
        // status is NOT 403 Forbidden and NOT 401 Unauthorized.
        var status = web.post().uri(path)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .returnResult(Void.class)
                .getStatus();
        org.assertj.core.api.Assertions.assertThat(status.value())
                .as("authorization passed for path %s — got %s", path, status)
                .isNotIn(401, 403);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/projects/11111111-1111-1111-1111-111111111111/stages/recipes/finalize",
        "/api/v1/projects/11111111-1111-1111-1111-111111111111/stages/cutover/finalize",
        "/api/v1/projects/11111111-1111-1111-1111-111111111111/stages/reports/build"
    })
    void adminIsAlsoAllowed(String path) throws Exception {
        String token = mintToken("carol@envestnet.local",
                                 List.of("ENGINEER", "TECH_LEAD", "ADMIN"));
        var status = web.post().uri(path)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .returnResult(Void.class)
                .getStatus();
        org.assertj.core.api.Assertions.assertThat(status.value()).isNotIn(401, 403);
    }

    @Test
    void engineerStillReachesNonFinalizeRoutes() throws Exception {
        String token = mintToken("alice@envestnet.local", List.of("ENGINEER"));
        // /api/v1/me is gated by hasRole("ENGINEER") and it has a local
        // controller (not routed downstream), so we can fully assert 200.
        web.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void anonymousFinalizeIsUnauthorizedNotForbidden() {
        // No token at all — should be 401, not 403, because the security
        // filter rejects unauthenticated traffic before the role check.
        web.post().uri(FINALIZE_PATHS[0])
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void tokenWithoutEngineerRoleIsForbiddenEverywhere() throws Exception {
        // Hypothetical persona with NO role at all — production IdPs that
        // forget to provision the ENGINEER claim will land users here.
        String token = mintToken("nobody@envestnet.local", List.of());
        web.get().uri("/api/v1/me")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isForbidden();
    }

    /* ---------------- helpers ---------------- */

    private static String mintToken(String email, List<String> roles) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuerUri)
                .subject(email)
                .audience("atlas-spa")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("email", email)
                .claim("name", email.split("@")[0])
                .claim("roles", roles)
                .claim("atlas_demo", true)
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaJwk.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(rsaJwk));
        return jwt.serialize();
    }
}
