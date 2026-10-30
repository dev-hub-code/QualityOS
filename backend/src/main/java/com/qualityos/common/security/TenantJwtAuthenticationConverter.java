package com.qualityos.common.security;

import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Checks the membership and loads permissions on every request instead of trusting token contents, so that a
 * deactivation or role change takes effect at once rather than when the token expires.
 */
@Component
public class TenantJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String TENANT_CLAIM = "tid";

    private final TenantAccessResolver accessResolver;

    TenantJwtAuthenticationConverter(TenantAccessResolver accessResolver) {
        this.accessResolver = accessResolver;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID tenantId = UUID.fromString(jwt.getClaimAsString(TENANT_CLAIM));
        List<SimpleGrantedAuthority> authorities = accessResolver.resolvePermissions(tenantId, userId)
                .orElseThrow(() -> new InvalidBearerTokenException("Membership is not active"))
                .stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        return new JwtAuthenticationToken(jwt, authorities, userId.toString());
    }
}
