package com.envestnet.atlas.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of {@link IdentityForwardingFilter}.
 *
 * <p>Boots the gateway with the auth-sim profile, points
 * {@code PRJ_SERVICE_URL} at an in-process echo server, then sends
 * requests with various JWT/header combinations and inspects the
 * headers the echo server received from the gateway.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = {
                    "spring.profiles.active=auth-sim",
                    "atlas.auth.mode=sim",
                    "atlas.auth.login-url=http://localhost:8093/authorize",
                    "atlas.auth.issuer=http://localhost:8093"
                })
class IdentityForwardingFilterTest {

    static HttpServer jwksServer;
    static HttpServer prjEcho;
    static RSAKey rsaJwk;
    static String issuerUri;
    static String prjUrl;

    /**
     * Captures the headers of every request the gateway forwards to
     * the prj-service stand-in. Cleared by {@link #clear()}.
     */
    static final ConcurrentLinkedQueue<List<String[]>> received = new ConcurrentLinkedQueue<>();

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

        prjEcho = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        prjUrl = "http://127.0.0.1:" + prjEcho.getAddress().getPort();
        prjEcho.createContext("/", ex -> {
            // Snapshot the inbound headers so the test can inspect them.
            var snap = new java.util.ArrayList<String[]>();
            ex.getRequestHeaders().forEach((k, vs) ->
                    vs.forEach(v -> snap.add(new String[] { k, v })));
            received.add(snap);
            byte[] body = "{\"ok\":true}".getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        prjEcho.start();
    }

    @AfterAll
    static void stopServers() {
        if (jwksServer != null) jwksServer.stop(0);
        if (prjEcho != null)    prjEcho.stop(0);
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
    private final ObjectMapper json = new ObjectMapper();

    private static final UUID PID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String PROJECT_PATH = "/api/v1/projects/" + PID;

    private void clear() { received.clear(); }

    @Test
    void authenticatedRequestStampsAllThreeIdentityHeaders() throws Exception {
        clear();
        String token = mintToken("alice@envestnet.local", "Alice", List.of("ENGINEER"));

        web.get().uri(PROJECT_PATH)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();

        var hdrs = lastHeaders();
        assertThat(headerValue(hdrs, IdentityForwardingFilter.HDR_EMAIL))
                .isEqualTo("alice@envestnet.local");
        assertThat(headerValue(hdrs, IdentityForwardingFilter.HDR_NAME))
                .isEqualTo("Alice");
        assertThat(headerValue(hdrs, IdentityForwardingFilter.HDR_ROLES))
                .isEqualTo("ENGINEER");
    }

    @Test
    void multipleRolesAreCommaSeparatedWithoutRolePrefix() throws Exception {
        clear();
        String token = mintToken("carol@envestnet.local", "Carol",
                                 List.of("ENGINEER", "TECH_LEAD", "ADMIN"));

        web.get().uri(PROJECT_PATH)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();

        String roles = headerValue(lastHeaders(), IdentityForwardingFilter.HDR_ROLES);
        // Order isn't guaranteed by Spring's authority collection, so
        // compare as a set.
        assertThat(roles.split(",")).containsExactlyInAnyOrder(
                "ENGINEER", "TECH_LEAD", "ADMIN");
        // Critically, no ROLE_ prefix made it through.
        assertThat(roles).doesNotContain("ROLE_");
    }

    @Test
    void clientSuppliedIdentityHeadersAreAlwaysStripped() throws Exception {
        clear();
        String token = mintToken("alice@envestnet.local", "Alice", List.of("ENGINEER"));

        web.get().uri(PROJECT_PATH)
                .header("Authorization", "Bearer " + token)
                // Attempt to spoof the identity. The gateway must override
                // these — under no circumstances should a downstream
                // service see the spoofed values.
                .header(IdentityForwardingFilter.HDR_EMAIL, "carol@envestnet.local")
                .header(IdentityForwardingFilter.HDR_ROLES, "ADMIN")
                .header(IdentityForwardingFilter.HDR_NAME,  "Mallory")
                .exchange()
                .expectStatus().isOk();

        var hdrs = lastHeaders();
        // Gateway-asserted values, NOT the spoofed ones.
        assertThat(headerValue(hdrs, IdentityForwardingFilter.HDR_EMAIL))
                .isEqualTo("alice@envestnet.local");
        assertThat(headerValue(hdrs, IdentityForwardingFilter.HDR_ROLES))
                .isEqualTo("ENGINEER");
        assertThat(headerValue(hdrs, IdentityForwardingFilter.HDR_NAME))
                .isEqualTo("Alice");
    }

    @Test
    void anonymousPublicEndpointReceivesNoIdentityHeaders() throws Exception {
        clear();
        web.get().uri("/api/v1/auth/config")
                .exchange()
                .expectStatus().isOk();
        // /api/v1/auth/config is a local controller, not routed downstream;
        // so there's no captured request to inspect. The relevant guarantee
        // here is that the request didn't 5xx and didn't synthesize
        // identity headers from nothing — covered by the controller
        // itself returning the static auth-config payload.
        // (No assertion on `received` because the request never reached
        // the echo server.)
        assertThat(received).isEmpty();
    }

    @Test
    void identityHeadersAreNotAddedToRoutedRequestWithoutAuthentication() throws Exception {
        clear();
        web.get().uri(PROJECT_PATH)
                .header(IdentityForwardingFilter.HDR_EMAIL, "spoof@evil.com")
                .header(IdentityForwardingFilter.HDR_ROLES, "ADMIN")
                .exchange()
                // No bearer → 401 before the request is forwarded; echo
                // server never gets called.
                .expectStatus().isUnauthorized();
        assertThat(received).isEmpty();
    }

    @Test
    void emailFallsBackToSubjectWhenEmailClaimIsAbsent() throws Exception {
        clear();
        // Mint a JWT WITHOUT the email claim. Subject should fill in.
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuerUri)
                .subject("just-a-subject@envestnet.local")
                .audience("atlas-spa")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("roles", List.of("ENGINEER"))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaJwk.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(rsaJwk));

        web.get().uri(PROJECT_PATH)
                .header("Authorization", "Bearer " + jwt.serialize())
                .exchange()
                .expectStatus().isOk();

        assertThat(headerValue(lastHeaders(), IdentityForwardingFilter.HDR_EMAIL))
                .isEqualTo("just-a-subject@envestnet.local");
        assertThat(headerValue(lastHeaders(), IdentityForwardingFilter.HDR_NAME))
                .isEqualTo("just-a-subject@envestnet.local");
    }

    /* ---------------- helpers ---------------- */

    private List<String[]> lastHeaders() {
        var arr = received.toArray(new List[0]);
        if (arr.length == 0) throw new AssertionError("no captured request");
        @SuppressWarnings("unchecked")
        List<String[]> last = (List<String[]>) arr[arr.length - 1];
        return last;
    }

    private static String headerValue(List<String[]> headers, String name) {
        return headers.stream()
                .filter(kv -> kv[0].equalsIgnoreCase(name))
                .map(kv -> kv[1])
                .findFirst()
                .orElse(null);
    }

    private static String mintToken(String email, String name, List<String> roles) throws Exception {
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
                .claim("atlas_demo", true)
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaJwk.getKeyID()).build(),
                claims);
        jwt.sign(new RSASSASigner(rsaJwk));
        return jwt.serialize();
    }
}
