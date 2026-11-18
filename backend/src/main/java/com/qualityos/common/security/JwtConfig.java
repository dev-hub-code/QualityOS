package com.qualityos.common.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

/**
 * Signs tokens with one key, named in each token's {@code kid} header, and verifies them with that key and, during a
 * rotation, the previous one until the date it stops being trusted. See docs/SECURITY_REVIEW.md, "Rotating the signing key".
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    private static final int RSA_KEY_BITS = 2048;

    /** The keys in use: the one that signs, and a previous public key still trusted for a while, if any. */
    public record JwtKeys(RSAKey signing, RSAKey previous, Instant previousTrustedUntil) {

        /** Verification keys trusted at {@code now}: the signing key, and the previous one until it is retired. */
        List<JWK> trusted(Instant now) {
            List<JWK> keys = new ArrayList<>(List.of(signing.toPublicJWK()));
            if (previous != null && now.isBefore(previousTrustedUntil)) {
                keys.add(previous);
            }
            return keys;
        }
    }

    @Bean
    JwtKeys jwtKeys(JwtProperties properties) throws NoSuchAlgorithmException, JOSEException {
        RSAPublicKey publicKey;
        RSAPrivateKey privateKey;
        if (StringUtils.hasText(properties.privateKey()) && StringUtils.hasText(properties.publicKey())) {
            privateKey = RsaKeyConverters.pkcs8().convert(stream(properties.privateKey()));
            publicKey = RsaKeyConverters.x509().convert(stream(properties.publicKey()));
        } else if (properties.allowEphemeralKeys()) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_BITS);
            var pair = generator.generateKeyPair();
            publicKey = (RSAPublicKey) pair.getPublic();
            privateKey = (RSAPrivateKey) pair.getPrivate();
        } else {
            throw new IllegalStateException("JWT_PRIVATE_KEY and JWT_PUBLIC_KEY must be configured");
        }
        RSAKey signing = named(new RSAKey.Builder(publicKey).privateKey(privateKey), properties.keyId(), publicKey);

        if (!StringUtils.hasText(properties.previousPublicKey())) {
            return new JwtKeys(signing, null, null);
        }
        if (properties.previousKeyTrustedUntil() == null) {
            throw new IllegalStateException(
                    "JWT_PREVIOUS_KEY_TRUSTED_UNTIL must be set with JWT_PREVIOUS_PUBLIC_KEY, so the old key retires");
        }
        RSAPublicKey previousKey = RsaKeyConverters.x509().convert(stream(properties.previousPublicKey()));
        RSAKey previous = named(new RSAKey.Builder(previousKey), null, previousKey);
        if (previous.getKeyID().equals(signing.getKeyID())) {
            throw new IllegalStateException("The previous JWT key is the same as the signing key");
        }
        return new JwtKeys(signing, previous, properties.previousKeyTrustedUntil());
    }

    @Bean
    JwtEncoder jwtEncoder(JwtKeys keys) {
        // With a key id on the key, every token names it in its kid header.
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(keys.signing())));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtKeys keys, JwtProperties properties) {
        return decoder(keys, properties, Clock.systemUTC());
    }

    /** Checks the signature against the keys trusted at that moment, then issuer, audience and expiry. */
    static JwtDecoder decoder(JwtKeys keys, JwtProperties properties, Clock clock) {
        JWKSource<SecurityContext> trusted = (selector, context) -> selector.select(new JWKSet(keys.trusted(clock.instant())));
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, trusted));
        // Expiry and the other claims are checked by the validators below, as for any Spring decoder.
        processor.setJWTClaimsSetVerifier((claims, context) -> { });
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                jwt -> hasAudience(jwt.getAudience(), properties.audience())));
        return decoder;
    }

    private static RSAKey named(RSAKey.Builder builder, String keyId, RSAPublicKey publicKey) throws JOSEException {
        String id = StringUtils.hasText(keyId) ? keyId.trim() : new RSAKey.Builder(publicKey).build().computeThumbprint().toString();
        return builder.keyID(id).algorithm(JWSAlgorithm.RS256).build();
    }

    private static OAuth2TokenValidatorResult hasAudience(List<String> audiences, String expected) {
        return audiences != null && audiences.contains(expected)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null));
    }

    private static ByteArrayInputStream stream(String pem) {
        return new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8));
    }
}
