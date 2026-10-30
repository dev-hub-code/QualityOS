package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.common.util.UuidV7;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** Two organisations attack each other through the API, then through SQL with the application layer removed. */
class TenantIsolationIT extends AbstractIntegrationTest {

    /** Reached by the auth service through a token hash, before a tenant is known. See TENANT_ISOLATION.md. */
    private static final List<String> NO_RLS_BY_DESIGN = List.of("refresh_tokens");

    private Session tenantA;
    private Session tenantB;

    @BeforeEach
    void twoOrganizations() throws Exception {
        tenantA = register();
        tenantB = register();
    }

    // --- Through the API ---

    @Test
    void anotherOrganizationCannotBeReadOrChanged() throws Exception {
        String path = "/api/v1/organizations/" + tenantA.tenantId();

        as(tenantB, get(path)).andExpect(status().isNotFound());
        as(tenantB, put(path).contentType("application/json").content(json.writeValueAsString(Map.of(
                        "name", "Hijacked", "countryCode", "IN", "timezone", "UTC", "currency", "INR",
                        "locale", "en", "version", 0))))
                .andExpect(status().isNotFound());
    }

    @Test
    void userListNeverContainsAnotherTenantsMembers() throws Exception {
        as(tenantB, get("/api/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(content().string(not(containsString(tenantA.userId().toString()))));
    }

    @Test
    void anotherTenantsMembershipCannotBeChanged() throws Exception {
        String membership = membershipId(tenantA, tenantA.userId());

        as(tenantB, put("/api/v1/users/" + membership + "/roles").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("roleIds", List.of(roleId(tenantB, "VIEWER"))))))
                .andExpect(status().isNotFound());
        as(tenantB, postJson("/api/v1/users/" + membership + "/deactivate", Map.of()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherTenantsCustomRoleIsInvisibleAndUnusable() throws Exception {
        String body = as(tenantA, postJson("/api/v1/roles", Map.of("name", "Private", "permissions", List.of("NCR_VIEW"))))
                .andReturn().getResponse().getContentAsString();
        String roleId = JsonPath.read(body, "$.data.id");

        as(tenantB, get("/api/v1/roles")).andExpect(content().string(not(containsString(roleId))));
        as(tenantB, put("/api/v1/roles/" + roleId).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("name", "X", "permissions", List.of("NCR_VIEW")))))
                .andExpect(status().isNotFound());
        as(tenantB, delete("/api/v1/roles/" + roleId)).andExpect(status().isNotFound());
        as(tenantB, postJson("/api/v1/users", Map.of(
                        "email", uniqueEmail(), "fullName", "X", "initialPassword", PASSWORD, "roleIds", List.of(roleId))))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void auditTrailIsPrivateToEachTenant() throws Exception {
        as(tenantB, get("/api/v1/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(tenantA.tenantId().toString()))))
                .andExpect(content().string(not(containsString(tenantA.userId().toString()))));
    }

    @Test
    void clientCannotChooseATenantWithHeadersOrParameters() throws Exception {
        mvc.perform(get("/api/v1/users")
                        .param("tenantId", tenantA.tenantId().toString())
                        .header("X-Tenant-Id", tenantA.tenantId().toString())
                        .header("Authorization", tenantB.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(content().string(not(containsString(tenantA.userId().toString()))));
    }

    @Test
    void switchingToATenantWithoutMembershipIsRefused() throws Exception {
        as(tenantB, postJson("/api/v1/auth/switch-tenant", Map.of(
                        "tenantId", tenantA.tenantId(), "refreshToken", tenantB.refreshToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void preferredTenantAtLoginIsIgnoredWithoutMembership() throws Exception {
        String body = mvc.perform(postJson("/api/v1/auth/login", Map.of(
                        "email", tenantB.email(), "password", PASSWORD, "tenantId", tenantA.tenantId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(body, "$.data.tenantId")).isEqualTo(tenantB.tenantId().toString());
    }

    @Test
    void switchingBetweenOwnOrganizationsScopesData() throws Exception {
        as(tenantA, postJson("/api/v1/users", Map.of(
                        "email", tenantB.email(), "fullName", "X", "roleIds", List.of(roleId(tenantA, "VIEWER")))))
                .andExpect(status().isCreated());

        String body = as(tenantB, postJson("/api/v1/auth/switch-tenant", Map.of(
                        "tenantId", tenantA.tenantId(), "refreshToken", tenantB.refreshToken())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Session switched = toSession(tenantB.email(), body);

        as(switched, get("/api/v1/auth/me"))
                .andExpect(jsonPath("$.data.tenantId").value(tenantA.tenantId().toString()));
        as(switched, get("/api/v1/users")).andExpect(status().isForbidden());
    }

    @Test
    void tokenForAnOrganizationTheUserLeftStopsWorking() throws Exception {
        as(tenantA, postJson("/api/v1/users", Map.of(
                        "email", tenantB.email(), "fullName", "X", "roleIds", List.of(roleId(tenantA, "VIEWER")))))
                .andExpect(status().isCreated());
        String body = as(tenantB, postJson("/api/v1/auth/switch-tenant", Map.of(
                        "tenantId", tenantA.tenantId(), "refreshToken", tenantB.refreshToken())))
                .andReturn().getResponse().getContentAsString();
        Session switched = toSession(tenantB.email(), body);

        as(tenantA, postJson("/api/v1/users/" + membershipId(tenantA, tenantB.userId()) + "/deactivate", Map.of()))
                .andExpect(status().isNoContent());

        as(switched, get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    // --- Row-level security, with the application layer bypassed ---

    @Test
    void withoutAnyContextNoTenantRowsAreVisible() {
        Long visible = inTransactionAs(null, null, () -> jdbc.queryForObject(
                "SELECT (SELECT count(*) FROM tenants) + (SELECT count(*) FROM tenant_memberships)"
                        + " + (SELECT count(*) FROM user_roles) + (SELECT count(*) FROM audit_logs)",
                Long.class));

        assertThat(visible).isZero();
    }

    @Test
    void queryOutsideATransactionSeesNoTenantRows() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants", Long.class)).isZero();
    }

    @Test
    void unfilteredSqlOnlyReturnsTheCurrentTenant() {
        List<UUID> visible = inTransactionAs(tenantB.tenantId(), tenantB.userId(), () ->
                jdbc.queryForList("SELECT tenant_id FROM tenant_memberships", UUID.class));

        assertThat(visible).containsOnly(tenantB.tenantId());
    }

    @Test
    void anotherTenantsRowsCannotBeUpdatedOrDeleted() {
        int changed = inTransactionAs(tenantB.tenantId(), tenantB.userId(), () ->
                jdbc.update("UPDATE tenants SET name = 'Hijacked' WHERE id = ?", tenantA.tenantId())
                        + jdbc.update("DELETE FROM tenant_memberships WHERE tenant_id = ?", tenantA.tenantId()));

        assertThat(changed).isZero();
    }

    @Test
    void rowsCannotBeInsertedIntoAnotherTenant() {
        assertThatThrownBy(() -> inTransactionAs(tenantB.tenantId(), tenantB.userId(), () ->
                jdbc.update("INSERT INTO tenant_memberships (id, tenant_id, user_id) VALUES (?, ?, ?)",
                        UuidV7.generate(), tenantA.tenantId(), tenantB.userId())))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void userRoleCannotReferenceAnotherTenantsMembership() {
        UUID foreignMembership = inTransactionAs(tenantA.tenantId(), tenantA.userId(), () ->
                jdbc.queryForObject("SELECT id FROM tenant_memberships LIMIT 1", UUID.class));
        UUID role = inTransactionAs(tenantB.tenantId(), tenantB.userId(), () ->
                jdbc.queryForObject("SELECT id FROM roles WHERE code = 'VIEWER'", UUID.class));

        assertThatThrownBy(() -> inTransactionAs(tenantB.tenantId(), tenantB.userId(), () ->
                jdbc.update("INSERT INTO user_roles (id, tenant_id, membership_id, role_id) VALUES (?, ?, ?, ?)",
                        UuidV7.generate(), tenantB.tenantId(), foreignMembership, role)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("fk_user_roles_membership");
    }

    @Test
    void systemRolesCannotBeAlteredThroughSql() {
        int changed = inTransactionAs(tenantB.tenantId(), tenantB.userId(), () ->
                jdbc.update("UPDATE roles SET name = 'Hacked' WHERE tenant_id IS NULL"));

        assertThat(changed).isZero();
    }

    @Test
    void systemRolePermissionsCannotBeWidened() {
        assertThatThrownBy(() -> inTransactionAs(tenantB.tenantId(), tenantB.userId(), () -> jdbc.update("""
                INSERT INTO role_permissions (role_id, permission_id)
                SELECT r.id, p.id FROM roles r, permissions p
                WHERE r.code = 'VIEWER' AND p.code = 'USER_MANAGE'
                """)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void auditLogIsAppendOnlyForTheApplication() {
        for (String sql : List.of("UPDATE audit_logs SET action = 'X'", "DELETE FROM audit_logs", "TRUNCATE audit_logs")) {
            assertThatThrownBy(() -> inTransactionAs(tenantB.tenantId(), tenantB.userId(), () -> jdbc.update(sql)))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining("permission denied");
        }
    }

    @Test
    void auditEventCannotBeForgedForAnotherTenant() {
        assertThatThrownBy(() -> inTransactionAs(tenantB.tenantId(), tenantB.userId(), () ->
                jdbc.update("INSERT INTO audit_logs (id, tenant_id, action, entity_type) VALUES (?, ?, 'X', 'X')",
                        UuidV7.generate(), tenantA.tenantId())))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void tenantSettingDoesNotLeakThroughTheConnectionPool() {
        inTransactionAs(tenantA.tenantId(), tenantA.userId(), () -> jdbc.queryForObject("SELECT 1", Integer.class));

        for (int i = 0; i < 20; i++) {
            assertThat(inTransactionAs(null, null, () ->
                    jdbc.queryForObject("SELECT count(*) FROM tenants", Long.class))).isZero();
        }
    }

    /** Fails the build for any future table with a tenant_id but no enforced policy. */
    @Test
    void everyTenantOwnedTableEnforcesRowLevelSecurity() {
        List<String> unprotected = jdbc.queryForList("""
                SELECT c.relname
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
                JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
                WHERE c.relkind = 'r'
                  AND NOT (c.relrowsecurity AND c.relforcerowsecurity
                           AND EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid))
                """, String.class);

        assertThat(unprotected).containsExactlyInAnyOrderElementsOf(NO_RLS_BY_DESIGN);
    }
}
