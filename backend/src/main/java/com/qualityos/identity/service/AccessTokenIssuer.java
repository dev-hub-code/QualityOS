package com.qualityos.identity.service;

import com.qualityos.common.security.JwtProperties;
import com.qualityos.common.security.TenantJwtAuthenticationConverter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
class AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    AccessTokenIssuer(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    String issue(UUID userId, UUID tenantId) {
        return encode(claims(userId).claim(TenantJwtAuthenticationConverter.TENANT_CLAIM, tenantId.toString()));
    }

    /** Has no tenant, and security allows it nothing beyond asking about its own verification. */
    String issuePendingVerification(UUID userId) {
        return encode(claims(userId).claim(TenantJwtAuthenticationConverter.PENDING_VERIFICATION_CLAIM, true));
    }

    private JwtClaimsSet.Builder claims(UUID userId) {
        Instant now = Instant.now();
        return JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString())
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()));
    }

    private String encode(JwtClaimsSet.Builder claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    long ttlSeconds() {
        return properties.accessTokenTtl().toSeconds();
    }
}
