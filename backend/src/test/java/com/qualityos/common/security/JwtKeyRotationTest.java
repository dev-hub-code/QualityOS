package com.qualityos.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Rotating the signing key: new tokens name the new key, and tokens from the old one stop working on a set date. */
class JwtKeyRotationTest {

    private static final Instant ROTATED = Instant.parse("2026-11-18T10:00:00Z");
    private static final Instant TRUSTED_UNTIL = ROTATED.plus(Duration.ofHours(1));

    private final KeyPair oldKey = generate();
    private final KeyPair newKey = generate();
    private final JwtConfig config = new JwtConfig();

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + type
                + "-----\n";
    }

    private JwtProperties properties(KeyPair signing, String keyId, KeyPair previous, Instant trustedUntil) {
        return properties(signing, keyId, previous, null, trustedUntil);
    }

    private JwtProperties properties(KeyPair signing, String keyId, KeyPair previous, String previousKeyId,
            Instant trustedUntil) {
        return new JwtProperties("qualityos", "qualityos-api", Duration.ofMinutes(15), Duration.ofDays(14),
                pem("PRIVATE KEY", signing.getPrivate().getEncoded()), pem("PUBLIC KEY", signing.getPublic().getEncoded()),
                keyId, previous == null ? null : pem("PUBLIC KEY", previous.getPublic().getEncoded()), previousKeyId, trustedUntil,
                false);
    }

    private static String token(KeyPair key, String keyId, Instant issuedAt) {
        RSAKey.Builder jwk = new RSAKey.Builder((RSAPublicKey) key.getPublic()).privateKey((RSAPrivateKey) key.getPrivate());
        if (keyId != null) {
            jwk.keyID(keyId);
        }
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(jwk.build())))
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), JwtClaimsSet.builder()
                        .issuer("qualityos").audience(List.of("qualityos-api")).subject("someone")
                        .issuedAt(issuedAt).expiresAt(issuedAt.plus(Duration.ofDays(3650))).build()))
                .getTokenValue();
    }

    private static String keyIdOf(String token) throws Exception {
        return SignedJWT.parse(token).getHeader().getKeyID();
    }

    private JwtDecoder decoderAt(JwtProperties properties, Instant now) throws Exception {
        return JwtConfig.decoder(config.jwtKeys(properties), properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void newTokensNameTheSigningKey() throws Exception {
        JwtConfig.JwtKeys keys = config.jwtKeys(properties(newKey, null, null, null));
        String token = config.jwtEncoder(keys).encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(),
                JwtClaimsSet.builder().subject("someone").issuedAt(ROTATED).expiresAt(ROTATED.plusSeconds(60)).build()))
                .getTokenValue();

        // Left unset, the id is the key's thumbprint, the same on every instance holding the key.
        assertThat(keyIdOf(token)).isEqualTo(new RSAKey.Builder((RSAPublicKey) newKey.getPublic()).build()
                .computeThumbprint().toString());

        JwtConfig.JwtKeys named = config.jwtKeys(properties(newKey, "2026-11", null, null));
        String namedToken = config.jwtEncoder(named).encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(),
                JwtClaimsSet.builder().subject("someone").issuedAt(ROTATED).expiresAt(ROTATED.plusSeconds(60)).build()))
                .getTokenValue();
        assertThat(keyIdOf(namedToken)).isEqualTo("2026-11");
    }

    @Test
    void theOldKeyIsTrustedUntilItsDateThenRefused() throws Exception {
        JwtProperties rotated = properties(newKey, null, oldKey, TRUSTED_UNTIL);
        String fromOldKey = token(oldKey, null, ROTATED.minusSeconds(60));
        String fromNewKey = token(newKey, null, ROTATED.plusSeconds(60));

        JwtDecoder during = decoderAt(rotated, TRUSTED_UNTIL.minusSeconds(1));
        assertThat(during.decode(fromOldKey).getSubject()).isEqualTo("someone");
        assertThat(during.decode(fromNewKey).getSubject()).isEqualTo("someone");

        JwtDecoder after = decoderAt(rotated, TRUSTED_UNTIL);
        assertThatThrownBy(() -> after.decode(fromOldKey)).isInstanceOf(JwtException.class);
        assertThat(after.decode(fromNewKey).getSubject()).isEqualTo("someone");
    }

    @Test
    void anOldKeyWithItsOwnIdKeepsWorkingWhenThatIdIsGiven() throws Exception {
        // Before the rotation the key was named "2026-10"; its tokens carry that name, not its thumbprint.
        String fromOldKey = token(oldKey, "2026-10", ROTATED.minusSeconds(60));

        JwtDecoder withId = decoderAt(properties(newKey, "2026-11", oldKey, "2026-10", TRUSTED_UNTIL), ROTATED);
        assertThat(withId.decode(fromOldKey).getSubject()).isEqualTo("someone");
        assertThat(withId.decode(token(newKey, "2026-11", ROTATED)).getSubject()).isEqualTo("someone");

        // Without it, the old tokens are refused: which is why the runbook asks for JWT_PREVIOUS_KEY_ID.
        JwtDecoder withoutId = decoderAt(properties(newKey, "2026-11", oldKey, null, TRUSTED_UNTIL), ROTATED);
        assertThatThrownBy(() -> withoutId.decode(fromOldKey)).isInstanceOf(JwtException.class);
    }

    @Test
    void withoutARotationOnlyTheSigningKeyIsTrusted() throws Exception {
        JwtDecoder decoder = decoderAt(properties(newKey, null, null, null), ROTATED);

        assertThatThrownBy(() -> decoder.decode(token(oldKey, null, ROTATED))).isInstanceOf(JwtException.class);
        // A token from before key ids, with no kid header, still works with the key that signed it.
        assertThat(decoder.decode(token(newKey, null, ROTATED)).getSubject()).isEqualTo("someone");
    }

    @Test
    void aTokenNamingAnUnknownKeyIsRefused() throws Exception {
        JwtDecoder decoder = decoderAt(properties(newKey, "current", oldKey, TRUSTED_UNTIL), ROTATED);

        assertThatThrownBy(() -> decoder.decode(token(newKey, "someone-elses", ROTATED)))
                .isInstanceOf(JwtException.class);
        assertThat(decoder.decode(token(newKey, "current", ROTATED)).getSubject()).isEqualTo("someone");
    }

    @Test
    void aPreviousKeyMustHaveARetirementDate() {
        assertThatThrownBy(() -> config.jwtKeys(properties(newKey, null, oldKey, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PREVIOUS_KEY_TRUSTED_UNTIL");
        assertThatThrownBy(() -> config.jwtKeys(properties(newKey, null, newKey, TRUSTED_UNTIL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same as the signing key");
        assertThatThrownBy(() -> config.jwtKeys(properties(newKey, "same", oldKey, "same", TRUSTED_UNTIL)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PREVIOUS_KEY_ID");
    }
}
