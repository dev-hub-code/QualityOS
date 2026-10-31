package com.qualityos.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class AuthRateLimitFilterTest {

    private static final Instant START = Instant.parse("2026-10-31T10:00:00Z");

    private int call(AuthRateLimitFilter filter, String method, String path, String address)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(address);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    private AuthRateLimitFilter filter(Instant now) {
        return new AuthRateLimitFilter(2, JsonMapper.builder().build(), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void refusesTheAttemptAfterTheLimit() throws Exception {
        AuthRateLimitFilter filter = filter(START);
        assertThat(call(filter, "POST", "/api/v1/auth/login", "10.0.0.1")).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/v1/auth/password-reset/request", "10.0.0.1")).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/v1/auth/login", "10.0.0.1")).isEqualTo(429);
    }

    @Test
    void coversTheEmailVerificationEndpoints() throws Exception {
        AuthRateLimitFilter filter = filter(START);
        assertThat(call(filter, "POST", "/api/v1/auth/verify-email", "10.0.0.1")).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/v1/auth/resend-verification", "10.0.0.1")).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/v1/auth/verify-email", "10.0.0.1")).isEqualTo(429);
    }

    @Test
    void countsEachAddressSeparately() throws Exception {
        AuthRateLimitFilter filter = filter(START);
        call(filter, "POST", "/api/v1/auth/login", "10.0.0.1");
        call(filter, "POST", "/api/v1/auth/login", "10.0.0.1");
        assertThat(call(filter, "POST", "/api/v1/auth/login", "10.0.0.2")).isEqualTo(200);
    }

    @Test
    void leavesOtherRequestsAlone() throws Exception {
        AuthRateLimitFilter filter = filter(START);
        for (int request = 0; request < 5; request++) {
            assertThat(call(filter, "POST", "/api/v1/auth/refresh", "10.0.0.1")).isEqualTo(200);
            assertThat(call(filter, "GET", "/api/v1/auth/me", "10.0.0.1")).isEqualTo(200);
        }
    }

    @Test
    void aNewMinuteStartsAgain() throws Exception {
        Clock[] clock = {Clock.fixed(START, ZoneOffset.UTC)};
        AuthRateLimitFilter filter = new AuthRateLimitFilter(1, JsonMapper.builder().build(), new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return clock[0].instant();
            }
        });
        call(filter, "POST", "/api/v1/auth/login", "10.0.0.1");
        assertThat(call(filter, "POST", "/api/v1/auth/login", "10.0.0.1")).isEqualTo(429);
        clock[0] = Clock.fixed(START.plusSeconds(60), ZoneOffset.UTC);
        assertThat(call(filter, "POST", "/api/v1/auth/login", "10.0.0.1")).isEqualTo(200);
    }
}
