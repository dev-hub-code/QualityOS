package com.qualityos.common.security;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param privateKey PKCS#8 PEM, the key that signs every new token
 * @param publicKey X.509 PEM, its public half
 * @param keyId names the signing key in each token's {@code kid} header; left blank, the key's RFC 7638 thumbprint,
 *     which every instance with the same key works out alike
 * @param previousPublicKey X.509 PEM of the key signed with before the last rotation, trusted only to verify tokens
 *     it already signed. Blank when no rotation is under way
 * @param previousKeyId the {@code kid} the previous key's tokens carry: what {@code keyId} was before the rotation.
 *     Blank when it had none, so its tokens carry its thumbprint
 * @param previousKeyTrustedUntil when tokens signed with the previous key stop being accepted. Required with a
 *     previous key: at least the longest token lifetime after the new key went live
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
        String keyId,
        String previousPublicKey,
        String previousKeyId,
        Instant previousKeyTrustedUntil,
        boolean allowEphemeralKeys) {
}
