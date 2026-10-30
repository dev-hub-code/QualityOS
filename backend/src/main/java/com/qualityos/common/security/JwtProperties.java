package com.qualityos.common.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param privateKey PKCS#8 PEM
 * @param publicKey X.509 PEM
 * @param allowEphemeralKeys generate keys at startup when none are configured; local development only
 */
@ConfigurationProperties("qualityos.security.jwt")
public record JwtProperties(
        String issuer,
        String audience,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        String privateKey,
        String publicKey,
        boolean allowEphemeralKeys) {
}
