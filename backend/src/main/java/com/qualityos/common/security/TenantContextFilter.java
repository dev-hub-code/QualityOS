package com.qualityos.common.security;

import com.qualityos.common.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** Establishes the {@link TenantContext} from the verified token, the only source of tenant identity for requests. */
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        UUID userId = UUID.fromString(token.getToken().getSubject());
        // Only a token restricted to a pending account has no tenant, and such a token reaches no tenant data.
        String tenant = token.getToken().getClaimAsString(TenantJwtAuthenticationConverter.TENANT_CLAIM);
        UUID tenantId = tenant == null ? null : UUID.fromString(tenant);
        try (TenantContext.Scope ignored = TenantContext.open(tenantId, userId)) {
            chain.doFilter(request, response);
        }
    }
}
