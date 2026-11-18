package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;

import com.qualityos.common.security.AuthRateLimitFilter;
import com.qualityos.common.security.DatabaseAttemptCounter;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** The sign-in rate limit is kept in the database: shared by every instance, and kept across a restart. */
class AuthRateLimitIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:30Z");

    @Autowired
    private JdbcTemplate jdbc;

    /** A new filter and counter stand for another instance of the backend, or the same one after a restart. */
    private AuthRateLimitFilter instance(Instant now) {
        return new AuthRateLimitFilter(3, JsonMapper.builder().build(), Clock.fixed(now, ZoneOffset.UTC),
                new DatabaseAttemptCounter(jdbc));
    }

    private int login(AuthRateLimitFilter filter, String address) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(address);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    private static String address() {
        UUID id = UUID.randomUUID();
        return "10." + Math.floorMod(id.hashCode(), 250) + "." + Math.floorMod(id.getLeastSignificantBits(), 250) + "."
                + Math.floorMod(id.getMostSignificantBits(), 250);
    }

    @Test
    void instancesShareOneLimit() throws Exception {
        String address = address();
        AuthRateLimitFilter first = instance(NOW);
        AuthRateLimitFilter second = instance(NOW);

        assertThat(login(first, address)).isEqualTo(200);
        assertThat(login(second, address)).isEqualTo(200);
        assertThat(login(first, address)).isEqualTo(200);
        assertThat(login(second, address)).isEqualTo(429);
        assertThat(login(first, address)).isEqualTo(429);
        // Another address has its own count.
        assertThat(login(second, address())).isEqualTo(200);
    }

    @Test
    void aRestartDoesNotResetTheCount() throws Exception {
        String address = address();
        AuthRateLimitFilter before = instance(NOW);
        for (int attempt = 0; attempt < 3; attempt++) {
            login(before, address);
        }

        AuthRateLimitFilter afterRestart = instance(NOW.plusSeconds(5));
        assertThat(login(afterRestart, address)).isEqualTo(429);

        // The next minute starts again.
        assertThat(login(instance(NOW.plusSeconds(60)), address)).isEqualTo(200);
    }

    @Test
    void oldWindowsAreDeleted() throws Exception {
        jdbc.update("INSERT INTO auth_attempts VALUES ('address:old', ?, 5)", Timestamp.from(NOW.minusSeconds(3600)));

        login(instance(NOW), address());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_attempts WHERE bucket = 'address:old'", Integer.class))
                .isZero();
    }

    @Test
    void withoutTheDatabaseItStillLimitsOnThisInstance() throws Exception {
        JdbcTemplate broken = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:postgresql://127.0.0.1:1/none"));
        AuthRateLimitFilter filter = new AuthRateLimitFilter(2, JsonMapper.builder().build(),
                Clock.fixed(NOW, ZoneOffset.UTC), new DatabaseAttemptCounter(broken));
        String address = address();

        assertThat(login(filter, address)).isEqualTo(200);
        assertThat(login(filter, address)).isEqualTo(200);
        assertThat(login(filter, address)).isEqualTo(429);
    }
}
