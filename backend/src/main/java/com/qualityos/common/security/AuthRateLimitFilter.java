package com.qualityos.common.security;

import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.response.ApiError;
import com.qualityos.common.response.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Limits how fast one address can try credentials or trigger emails. Counts are per minute and per instance, so
 * running several instances multiplies the effective limit.
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MILLIS = 60_000;
    private static final int RETRY_AFTER_SECONDS = 60;
    private static final List<String> LIMITED_PREFIXES = List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/auth/password-reset",
            "/api/v1/auth/verify-email",
            "/api/v1/auth/resend-verification",
            "/api/v1/auth/invitations");

    private final int limit;
    private final JsonMapper json;
    private final Clock clock;
    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private volatile long currentWindow;

    public AuthRateLimitFilter(int limit, JsonMapper json, Clock clock) {
        this.limit = limit;
        this.json = json;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || LIMITED_PREFIXES.stream().noneMatch(request.getRequestURI()::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long window = clock.millis() / WINDOW_MILLIS;
        if (window != currentWindow) {
            currentWindow = window;
            counts.clear();
        }
        if (counts.computeIfAbsent(request.getRemoteAddr(), key -> new AtomicInteger()).incrementAndGet() > limit) {
            response.setStatus(ErrorCode.TOO_MANY_REQUESTS.status().value());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(json.writeValueAsString(ApiResponse.failure(new ApiError(
                    ErrorCode.TOO_MANY_REQUESTS.name(), "Too many attempts. Please wait a minute and try again."))));
            return;
        }
        chain.doFilter(request, response);
    }
}
