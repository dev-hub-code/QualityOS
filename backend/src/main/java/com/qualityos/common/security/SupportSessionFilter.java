package com.qualityos.common.security;

import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.response.ApiError;
import com.qualityos.common.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps a support session read-only unless the organisation let support make changes, and even then away from
 * access, billing and support access itself, whatever an endpoint allows. Records each record read and each change
 * made in the organisation's own support log.
 */
class SupportSessionFilter extends OncePerRequestFilter {

    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

    /** Signing out must still work. */
    private static final Set<String> WRITES_ALLOWED = Set.of("/api/v1/auth/logout");

    /**
     * Never changed in a support session, beside what the session's permissions leave out: the operator's own
     * account, who has access, billing, support access itself, and the organisation's support requests.
     */
    private static final List<String> NEVER_CHANGED = List.of("/api/v1/auth/", "/api/v1/users", "/api/v1/roles",
            "/api/v1/invitations", "/api/v1/support-access", "/api/v1/support/", "/api/v1/subscription",
            "/api/v1/billing", "/api/v1/organizations/current", "/api/v1/notification");

    /** What the app shell asks on every page, which says nothing about the organisation's records. */
    private static final List<String> NOT_LOGGED = List.of("/api/v1/auth/", "/api/v1/notifications",
            "/api/v1/platform-notices", "/api/v1/system/", "/api/v1/ncr-status-labels",
            "/api/v1/organizations/current/logo", "/api/v1/organizations/current/settings",
            "/api/v1/manufacturing/settings");

    private final TenantAccessResolver access;
    private final JsonMapper json;

    SupportSessionFilter(TenantAccessResolver access, JsonMapper json) {
        this.access = access;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String grant = authentication instanceof JwtAuthenticationToken token
                ? token.getToken().getClaimAsString(TenantJwtAuthenticationConverter.SUPPORT_CLAIM)
                : null;
        if (grant == null) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        JwtAuthenticationToken session = (JwtAuthenticationToken) authentication;
        boolean write = !READS.contains(request.getMethod()) && !WRITES_ALLOWED.contains(path);
        if (write) {
            boolean changes = session.getAuthorities().stream()
                    .anyMatch(held -> TenantAccessResolver.SUPPORT_CHANGES.equals(held.getAuthority()));
            if (!changes) {
                refuse(response, "Support access is read-only.");
                return;
            }
            if (NEVER_CHANGED.stream().anyMatch(path::startsWith)) {
                refuse(response, "Support cannot change this, even with access to make changes.");
                return;
            }
            // Audit entries made in this request are marked as support's.
            request.setAttribute(TenantJwtAuthenticationConverter.SUPPORT_GRANT_ATTRIBUTE, UUID.fromString(grant));
            chain.doFilter(request, response);
            if (response.getStatus() < 400) {
                access.recordSupportChange(
                        UUID.fromString(session.getToken().getClaimAsString(TenantJwtAuthenticationConverter.TENANT_CLAIM)),
                        UUID.fromString(session.getToken().getSubject()),
                        UUID.fromString(grant),
                        request.getMethod() + " " + path.replaceFirst("^/api/v1", ""));
            }
            return;
        }
        if ("GET".equals(request.getMethod()) && NOT_LOGGED.stream().noneMatch(path::startsWith)) {
            JwtAuthenticationToken token = (JwtAuthenticationToken) authentication;
            String query = request.getQueryString();
            access.recordSupportView(
                    UUID.fromString(token.getToken().getClaimAsString(TenantJwtAuthenticationConverter.TENANT_CLAIM)),
                    UUID.fromString(token.getToken().getSubject()),
                    UUID.fromString(grant),
                    path.replaceFirst("^/api/v1", "") + (query == null ? "" : "?" + query));
        }
        chain.doFilter(request, response);
    }

    private void refuse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(ErrorCode.FORBIDDEN.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(ApiResponse.failure(new ApiError(
                ErrorCode.FORBIDDEN.name(), message))));
    }
}
