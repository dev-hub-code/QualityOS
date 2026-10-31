package com.qualityos.common.security;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
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

    /** Marks a token that proves a password for an account still waiting to confirm its email address. */
    public static final String PENDING_VERIFICATION_CLAIM = "pev";

    /** Held by every token that belongs to an organisation. Permissions are additional to this. */
    public static final String MEMBER = "ORGANIZATION_MEMBER";
    public static final String PENDING_VERIFICATION = "PENDING_VERIFICATION";

    private final TenantAccessResolver accessResolver;

    TenantJwtAuthenticationConverter(TenantAccessResolver accessResolver) {
        this.accessResolver = accessResolver;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        String tenant = jwt.getClaimAsString(TENANT_CLAIM);
        if (tenant == null) {
            return pendingVerification(jwt, userId);
        }
        UUID tenantId = UUID.fromString(tenant);
        List<SimpleGrantedAuthority> authorities = Stream.concat(
                        Stream.of(MEMBER),
                        accessResolver.resolvePermissions(tenantId, userId)
                                .orElseThrow(() -> new InvalidBearerTokenException("Membership is not active"))
                                .stream())
                .map(SimpleGrantedAuthority::new)
                .toList();
        return new JwtAuthenticationToken(jwt, authorities, userId.toString());
    }

    private AbstractAuthenticationToken pendingVerification(Jwt jwt, UUID userId) {
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean(PENDING_VERIFICATION_CLAIM)) || !accessResolver.isOpenAccount(userId)) {
            throw new InvalidBearerTokenException("Token has no organisation");
        }
        return new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority(PENDING_VERIFICATION)), userId.toString());
    }
}
