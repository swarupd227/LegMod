package com.envestnet.atlas.fakeidp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the fake-IdP. Boots the service on a random port
 * and walks discovery → JWKS → /authorize (302 → /login) → POST /login
 * (302 with session cookie) → /authorize again (302 with code) → POST
 * /token (JWT) → verify the JWT signature against JWKS.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OidcSmokeTest {

    @LocalServerPort int port;
    private RestTemplate http;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // We need a client that does NOT auto-follow redirects so we can
        // assert each step (302 → /login, 302 → callback with code, etc.).
        // SimpleClientHttpRequestFactory exposes a hook for this.
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.HttpURLConnection conn, String httpMethod)
                    throws java.io.IOException {
                super.prepareConnection(conn, httpMethod);
                conn.setInstanceFollowRedirects(false);
            }
        };
        this.http = new RestTemplate(rf);
        // RestTemplate throws on 4xx/5xx by default — for an OIDC test we
        // want to inspect those status codes (e.g. expect 400 on bad PKCE).
        this.http.setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
            @Override public void handleError(org.springframework.http.client.ClientHttpResponse r) {}
        });
    }

    private String base() { return "http://localhost:" + port; }

    @Test
    void discoveryDocLooksRight() throws Exception {
        ResponseEntity<String> res = http.getForEntity(base() + "/.well-known/openid-configuration", String.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = json.readTree(res.getBody());
        assertThat(body.get("authorization_endpoint").asText()).endsWith("/authorize");
        assertThat(body.get("token_endpoint").asText()).endsWith("/token");
        assertThat(body.get("jwks_uri").asText()).endsWith("/.well-known/jwks.json");
        assertThat(body.get("code_challenge_methods_supported").toString()).contains("S256");
    }

    @Test
    void jwksReturnsAtLeastOneRsaKey() throws Exception {
        ResponseEntity<String> res = http.getForEntity(base() + "/.well-known/jwks.json", String.class);
        JWKSet set = JWKSet.parse(res.getBody());
        assertThat(set.getKeys()).isNotEmpty();
        assertThat(set.getKeys().get(0)).isInstanceOf(RSAKey.class);
    }

    @Test
    void fullPkceFlowYieldsValidJwt() throws Exception {
        // 1. PKCE
        String verifier = "atlas-test-verifier-" + System.nanoTime();
        String challenge = base64UrlNoPad(sha256(verifier));

        // 2. /authorize without session — expect 302 to /login
        String authorizeUrl = base() + "/authorize"
                + "?client_id=atlas-spa"
                + "&redirect_uri=http://localhost:3000/auth/callback"
                + "&response_type=code"
                + "&scope=openid+profile+email+roles"
                + "&code_challenge=" + challenge
                + "&code_challenge_method=S256"
                + "&state=xyz";
        ResponseEntity<Void> r1 = http.exchange(authorizeUrl, HttpMethod.GET, null, Void.class);
        assertThat(r1.getStatusCode().value()).isEqualTo(302);
        assertThat(r1.getHeaders().getLocation().toString()).contains("/login");

        // 3. POST /login as Alice
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("personaId", "alice@envestnet.local");
        form.add("resume", authorizeUrl);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<Void> r2 = http.exchange(base() + "/login", HttpMethod.POST,
                new HttpEntity<>(form, headers), Void.class);
        assertThat(r2.getStatusCode().value()).isEqualTo(302);
        // /login redirects back to the resume URL (which is /authorize)
        assertThat(r2.getHeaders().getLocation().toString()).contains("/authorize");

        // Capture the session cookie.
        String sessionCookie = r2.getHeaders().getFirst("Set-Cookie");
        assertThat(sessionCookie).startsWith("FAKEIDP_SESSION=");

        // 4. Re-do /authorize with session — expect 302 to redirect_uri with code.
        HttpHeaders withCookie = new HttpHeaders();
        withCookie.add("Cookie", sessionCookie);
        ResponseEntity<Void> r3 = http.exchange(authorizeUrl, HttpMethod.GET,
                new HttpEntity<>(withCookie), Void.class);
        assertThat(r3.getStatusCode().value()).isEqualTo(302);
        String back = r3.getHeaders().getLocation().toString();
        assertThat(back).contains("code=");
        assertThat(back).contains("state=xyz");
        String code = back.replaceAll(".*[?&]code=([^&]+).*", "$1");

        // 5. POST /token with code + verifier.
        MultiValueMap<String, String> tokenForm = new LinkedMultiValueMap<>();
        tokenForm.add("grant_type", "authorization_code");
        tokenForm.add("code", code);
        tokenForm.add("redirect_uri", "http://localhost:3000/auth/callback");
        tokenForm.add("client_id", "atlas-spa");
        tokenForm.add("code_verifier", verifier);
        HttpHeaders th = new HttpHeaders();
        th.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> r4 = http.exchange(base() + "/token", HttpMethod.POST,
                new HttpEntity<>(tokenForm, th), String.class);
        assertThat(r4.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode tokenBody = json.readTree(r4.getBody());
        String accessToken = tokenBody.get("access_token").asText();
        assertThat(accessToken).isNotEmpty();
        assertThat(tokenBody.get("token_type").asText()).isEqualTo("Bearer");

        // 6. Verify JWT signature using JWKS public key.
        JWKSet jwks = JWKSet.parse(http.getForObject(base() + "/.well-known/jwks.json", String.class));
        SignedJWT jwt = SignedJWT.parse(accessToken);
        RSAKey publicKey = (RSAKey) jwks.getKeyByKeyId(jwt.getHeader().getKeyID());
        JWSVerifier verifier2 = new RSASSAVerifier(publicKey.toRSAPublicKey());
        assertThat(jwt.verify(verifier2)).isTrue();
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);

        // Claims look right.
        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo("alice@envestnet.local");
        assertThat(claims.getStringClaim("email")).isEqualTo("alice@envestnet.local");
        assertThat(claims.getClaim("roles").toString()).contains("ENGINEER");
        assertThat(claims.getBooleanClaim("atlas_demo")).isTrue();
        assertThat(claims.getAudience()).containsExactly("atlas-spa");
    }

    @Test
    void pkceMismatchIsRejectedAtTokenEndpoint() throws Exception {
        String verifier = "v" + System.nanoTime();
        String challenge = base64UrlNoPad(sha256(verifier));

        String authorizeUrl = base() + "/authorize"
                + "?client_id=atlas-spa"
                + "&redirect_uri=http://localhost:3000/auth/callback"
                + "&response_type=code"
                + "&code_challenge=" + challenge
                + "&code_challenge_method=S256";

        // Login as alice.
        MultiValueMap<String, String> loginForm = new LinkedMultiValueMap<>();
        loginForm.add("personaId", "alice@envestnet.local");
        loginForm.add("resume", authorizeUrl);
        HttpHeaders lh = new HttpHeaders();
        lh.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<Void> login = http.exchange(base() + "/login", HttpMethod.POST,
                new HttpEntity<>(loginForm, lh), Void.class);
        String cookie = login.getHeaders().getFirst("Set-Cookie");

        HttpHeaders withCookie = new HttpHeaders();
        withCookie.add("Cookie", cookie);
        ResponseEntity<Void> auth = http.exchange(authorizeUrl, HttpMethod.GET,
                new HttpEntity<>(withCookie), Void.class);
        String code = auth.getHeaders().getLocation().toString().replaceAll(".*[?&]code=([^&]+).*", "$1");

        // Submit /token with the WRONG verifier.
        MultiValueMap<String, String> tokenForm = new LinkedMultiValueMap<>();
        tokenForm.add("grant_type", "authorization_code");
        tokenForm.add("code", code);
        tokenForm.add("redirect_uri", "http://localhost:3000/auth/callback");
        tokenForm.add("client_id", "atlas-spa");
        tokenForm.add("code_verifier", "wrong-verifier-entirely");
        HttpHeaders th = new HttpHeaders();
        th.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> r = http.exchange(base() + "/token", HttpMethod.POST,
                new HttpEntity<>(tokenForm, th), String.class);

        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody()).contains("invalid_grant");
    }

    @Test
    void unknownClientIdIsRejected() throws Exception {
        // Reuse a code from a successful login but flip client_id at /token.
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", "totally-bogus");
        form.add("redirect_uri", "http://localhost:3000/auth/callback");
        form.add("client_id", "atlas-spa");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        ResponseEntity<String> r = http.exchange(base() + "/token", HttpMethod.POST,
                new HttpEntity<>(form, h), String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody()).contains("invalid_grant");
    }

    private static byte[] sha256(String s) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(s.getBytes("US-ASCII"));
    }
    private static String base64UrlNoPad(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
    @SuppressWarnings("unused")
    private static Map<String, String> noop() { return Map.of(); }
}
