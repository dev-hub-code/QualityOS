package com.qualityos.common.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    private static final int RSA_KEY_BITS = 2048;

    @Bean
    KeyPair jwtKeyPair(JwtProperties properties) throws NoSuchAlgorithmException {
        if (StringUtils.hasText(properties.privateKey()) && StringUtils.hasText(properties.publicKey())) {
            RSAPrivateKey privateKey = RsaKeyConverters.pkcs8().convert(stream(properties.privateKey()));
            RSAPublicKey publicKey = RsaKeyConverters.x509().convert(stream(properties.publicKey()));
            return new KeyPair(publicKey, privateKey);
        }
        if (!properties.allowEphemeralKeys()) {
            throw new IllegalStateException("JWT_PRIVATE_KEY and JWT_PUBLIC_KEY must be configured");
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(RSA_KEY_BITS);
        return generator.generateKeyPair();
    }

    @Bean
    JwtEncoder jwtEncoder(KeyPair keyPair) {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey(keyPair.getPrivate())
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
    }

    @Bean
    JwtDecoder jwtDecoder(KeyPair keyPair, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                jwt -> hasAudience(jwt.getAudience(), properties.audience())));
        return decoder;
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
