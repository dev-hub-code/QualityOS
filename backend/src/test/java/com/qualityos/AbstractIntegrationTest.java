package com.qualityos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.common.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    protected static final String PASSWORD = "correct-horse-battery";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("qualityos")
            .withInitScript("db/test-roles.sql");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", POSTGRES::getJdbcUrl);
        registry.add("DB_APP_USER", () -> "qualityos_app");
        registry.add("DB_APP_PASSWORD", () -> "app");
        registry.add("DB_MIGRATION_USER", () -> "qualityos_migrator");
        registry.add("DB_MIGRATION_PASSWORD", () -> "migrator");
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    @Autowired
    protected JsonMapper json;

    protected record Session(String email, UUID userId, UUID tenantId, String accessToken, String refreshToken) {

        String bearer() {
            return "Bearer " + accessToken;
        }
    }

    protected static String uniqueEmail() {
        return "user" + SEQUENCE.incrementAndGet() + "-" + UUID.randomUUID() + "@Example.com";
    }

    protected Session register() throws Exception {
        return register(uniqueEmail());
    }

    protected Session register(String email) throws Exception {
        String body = mvc.perform(postJson("/api/v1/auth/register", Map.of(
                        "fullName", "Test User",
                        "email", email,
                        "password", PASSWORD,
                        "organizationName", "Org " + UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return toSession(email, body);
    }

    protected Session login(String email) throws Exception {
        String body = mvc.perform(postJson("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return toSession(email, body);
    }

    protected Session toSession(String email, String body) throws Exception {
        String access = JsonPath.read(body, "$.data.accessToken");
        String refresh = JsonPath.read(body, "$.data.refreshToken");
        UUID tenantId = UUID.fromString(JsonPath.read(body, "$.data.tenantId"));
        String me = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID userId = UUID.fromString(JsonPath.read(me, "$.data.user.id"));
        return new Session(email, userId, tenantId, access, refresh);
    }

    protected MockHttpServletRequestBuilder postJson(String path, Object body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    protected ResultActions as(Session session, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, session.bearer()));
    }

    protected String roleId(Session session, String code) throws Exception {
        String body = as(session, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/roles"))
                .andReturn().getResponse().getContentAsString();
        java.util.List<String> ids = JsonPath.read(body, "$.data[?(@.code == '" + code + "')].id");
        return ids.getFirst();
    }

    /** Adds a new member with one role and signs them in. */
    protected Session addMember(Session admin, String roleCode) throws Exception {
        String email = uniqueEmail();
        as(admin, postJson("/api/v1/users", Map.of(
                        "email", email,
                        "fullName", "Member",
                        "initialPassword", PASSWORD,
                        "roleIds", java.util.List.of(roleId(admin, roleCode)))))
                .andExpect(status().isCreated());
        return login(email);
    }

    protected String membershipId(Session admin, UUID userId) throws Exception {
        String body = as(admin, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/users"))
                .andReturn().getResponse().getContentAsString();
        java.util.List<String> ids = JsonPath.read(body, "$.data.items[?(@.userId == '" + userId + "')].membershipId");
        return ids.getFirst();
    }

    /** Runs raw SQL as the application role, with no help from the service layer or Hibernate. */
    protected <T> T inTransactionAs(UUID tenantId, UUID userId, java.util.function.Supplier<T> work) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return TenantContext.callAs(tenantId, userId, () -> tx.execute(status -> work.get()));
    }
}
