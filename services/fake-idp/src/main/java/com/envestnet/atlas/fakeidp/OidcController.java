package com.envestnet.atlas.fakeidp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OIDC endpoints implementing authorization-code flow with PKCE.
 *
 * <ul>
 *   <li>{@code GET /.well-known/openid-configuration} — discovery doc</li>
 *   <li>{@code GET /.well-known/jwks.json} — public JWKS</li>
 *   <li>{@code GET /authorize} — entry point; either redirects to /login
 *       (no session) or back to client with an auth code</li>
 *   <li>{@code GET /login} — persona picker (Thymeleaf)</li>
 *   <li>{@code POST /login} — establishes session, redirects to
 *       /authorize callback chain</li>
 *   <li>{@code POST /token} — exchanges code+verifier for a JWT</li>
 * </ul>
 *
 * State (sessions, codes) lives in-process. The service is single-replica
 * by design — it only exists in dev/demo profiles.
 */
@Controller
public class OidcController {

    private static final String SESSION_COOKIE = "FAKEIDP_SESSION";

    private final JwtIssuer issuer;
    private final FakeIdpApplication.Settings settings;

    /** sessionId → persona id. */
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    /** code → pending auth state. */
    private final Map<String, AuthCode> codes = new ConcurrentHashMap<>();

    public OidcController(JwtIssuer issuer, FakeIdpApplication.Settings settings) {
        this.issuer = issuer;
        this.settings = settings;
    }

    /* ---------------- Discovery & JWKS ---------------- */

    @GetMapping(value = "/.well-known/openid-configuration", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> discovery() {
        String iss = settings.issuer();
        return Map.ofEntries(
                Map.entry("issuer",                 iss),
                Map.entry("authorization_endpoint", iss + "/authorize"),
                Map.entry("token_endpoint",         iss + "/token"),
                Map.entry("jwks_uri",               iss + "/.well-known/jwks.json"),
                Map.entry("response_types_supported", List.of("code")),
                Map.entry("subject_types_supported",  List.of("public")),
                Map.entry("id_token_signing_alg_values_supported", List.of("RS256")),
                Map.entry("scopes_supported", List.of("openid", "profile", "email", "roles")),
                Map.entry("code_challenge_methods_supported", List.of("S256")),
                Map.entry("grant_types_supported", List.of("authorization_code"))
        );
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> jwks() {
        return issuer.publicJwks().toJSONObject();
    }

    /* ---------------- /authorize ---------------- */

    @GetMapping("/authorize")
    public ResponseEntity<Void> authorize(
            @RequestParam("client_id") String clientId,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam("response_type") String responseType,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "code_challenge", required = false) String codeChallenge,
            @RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod,
            @RequestParam(value = "nonce", required = false) String nonce,
            HttpServletRequest req
    ) {
        if (!"code".equals(responseType))
            return errorRedirect(redirectUri, "unsupported_response_type", state);

        String sessionId = readSession(req);
        // ConcurrentHashMap doesn't accept null keys, so guard explicitly.
        String personaId = sessionId == null ? null : sessions.get(sessionId);
        if (personaId == null) {
            // No session → bounce to /login with the original /authorize URL
            // preserved so we can resume after the user picks a persona.
            String resume = req.getRequestURL().append('?').append(req.getQueryString()).toString();
            String url = "/login?resume=" + URLEncoder.encode(resume, StandardCharsets.UTF_8);
            return ResponseEntity.status(302).location(URI.create(url)).build();
        }

        // Issue a one-shot code bound to this session, redirect_uri, and PKCE challenge.
        String code = randomToken(32);
        codes.put(code, new AuthCode(
                personaId, clientId, redirectUri,
                codeChallenge, codeChallengeMethod, nonce,
                Instant.now().plusSeconds(60)));

        StringBuilder back = new StringBuilder(redirectUri)
                .append(redirectUri.contains("?") ? "&" : "?")
                .append("code=").append(URLEncoder.encode(code, StandardCharsets.UTF_8));
        if (state != null)
            back.append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8));
        return ResponseEntity.status(302).location(URI.create(back.toString())).build();
    }

    /* ---------------- /login ---------------- */

    @GetMapping("/login")
    public String loginPage(@RequestParam(value = "resume", required = false) String resume,
                            Model model) {
        model.addAttribute("personas", settings.personas());
        model.addAttribute("resume", resume == null ? "" : resume);
        return "login";
    }

    @PostMapping("/login")
    public ResponseEntity<Void> loginSubmit(
            @RequestParam("personaId") String personaId,
            @RequestParam(value = "resume", required = false) String resume,
            HttpServletResponse res
    ) {
        var persona = settings.personas().stream()
                .filter(p -> p.id().equals(personaId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown persona: " + personaId));

        String sessionId = randomToken(32);
        sessions.put(sessionId, persona.id());

        Cookie cookie = new Cookie(SESSION_COOKIE, sessionId);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        // No Secure flag — demo runs on http://localhost. Real OIDC uses Secure.
        res.addCookie(cookie);

        String location = (resume != null && !resume.isBlank()) ? resume : "/";
        return ResponseEntity.status(302).location(URI.create(location)).build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest req, HttpServletResponse res) {
        String sessionId = readSession(req);
        if (sessionId != null) sessions.remove(sessionId);
        Cookie cookie = new Cookie(SESSION_COOKIE, "");
        cookie.setMaxAge(0);
        cookie.setPath("/");
        res.addCookie(cookie);
        return ResponseEntity.status(302).location(URI.create("/login")).build();
    }

    /* ---------------- /token ---------------- */

    @PostMapping(value = "/token",
                 consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<?> token(
            @RequestParam("grant_type") String grantType,
            @RequestParam("code") String code,
            @RequestParam("redirect_uri") String redirectUri,
            @RequestParam("client_id") String clientId,
            @RequestParam(value = "code_verifier", required = false) String codeVerifier
    ) {
        if (!"authorization_code".equals(grantType))
            return badRequest("unsupported_grant_type");

        AuthCode authCode = codes.remove(code);
        if (authCode == null)
            return badRequest("invalid_grant");
        if (Instant.now().isAfter(authCode.expiresAt))
            return badRequest("invalid_grant");
        if (!authCode.redirectUri.equals(redirectUri))
            return badRequest("invalid_grant");
        if (!authCode.clientId.equals(clientId))
            return badRequest("invalid_client");

        // PKCE: verifier hashes (S256) to the original challenge.
        if (authCode.codeChallenge != null) {
            if (codeVerifier == null) return badRequest("invalid_grant");
            String computed = s256(codeVerifier);
            if (!computed.equals(authCode.codeChallenge)) return badRequest("invalid_grant");
        }

        var persona = settings.personas().stream()
                .filter(p -> p.id().equals(authCode.personaId))
                .findFirst()
                .orElseThrow();
        SignedJWT jwt = issuer.mint(persona, clientId, authCode.nonce);

        return ResponseEntity.ok(new TokenResponse(
                jwt.serialize(),
                "Bearer",
                settings.accessTokenTtlSeconds(),
                jwt.serialize()  // For the demo, id_token == access_token. Real OIDC issues distinct tokens.
        ));
    }

    /* ---------------- helpers ---------------- */

    private String readSession(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (SESSION_COOKIE.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private static ResponseEntity<Void> errorRedirect(String redirectUri, String error, String state) {
        StringBuilder back = new StringBuilder(redirectUri)
                .append(redirectUri.contains("?") ? "&" : "?")
                .append("error=").append(error);
        if (state != null)
            back.append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8));
        return ResponseEntity.status(302).location(URI.create(back.toString())).build();
    }

    private static ResponseEntity<Map<String, String>> badRequest(String error) {
        return ResponseEntity.badRequest().body(Map.of("error", error));
    }

    private static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        new java.security.SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String s256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record AuthCode(
            String personaId,
            String clientId,
            String redirectUri,
            String codeChallenge,
            String codeChallengeMethod,
            String nonce,
            Instant expiresAt
    ) {}

    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type")   String tokenType,
            @JsonProperty("expires_in")   long expiresIn,
            @JsonProperty("id_token")     String idToken
    ) {}
}
