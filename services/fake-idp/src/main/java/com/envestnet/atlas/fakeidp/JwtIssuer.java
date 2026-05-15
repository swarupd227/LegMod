package com.envestnet.atlas.fakeidp;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Issues signed JWTs from an in-memory RSA keypair generated at startup.
 *
 * <p>Generating fresh on boot means the JWKS rotates per restart — that's
 * fine for demos and forces the gateway to re-fetch JWKS, which exercises
 * the production cache path.</p>
 */
@Component
public class JwtIssuer {

    private final FakeIdpApplication.Settings settings;
    private final RSAKey rsaJwk;
    private final RSASSASigner signer;

    public JwtIssuer(FakeIdpApplication.Settings settings) {
        this.settings = settings;
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            var pair = gen.generateKeyPair();
            this.rsaJwk = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
            this.signer = new RSASSASigner(rsaJwk);
        } catch (NoSuchAlgorithmException | JOSEException e) {
            throw new IllegalStateException("RSA keypair init failed", e);
        }
    }

    /** JWKS view (public-only) — served from /.well-known/jwks.json. */
    public JWKSet publicJwks() {
        return new JWKSet(rsaJwk.toPublicJWK());
    }

    public SignedJWT mint(FakeIdpApplication.Settings.Persona persona, String audience, String nonce) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(settings.accessTokenTtlSeconds());

        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(settings.issuer())
                .subject(persona.id())
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .jwtID(UUID.randomUUID().toString())
                .claim("email", persona.id())
                .claim("name", persona.name())
                .claim("title", persona.title())
                .claim("roles", List.copyOf(persona.roles()))
                // Custom claim the gateway uses to detect demo identities and
                // surface the "DEMO IDENTITY" banner. Real OIDC providers
                // never set this — its presence is the signal.
                .claim("atlas_demo", true);
        if (nonce != null) claims.claim("nonce", nonce);

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaJwk.getKeyID()).build(),
                claims.build());
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
        return jwt;
    }
}
