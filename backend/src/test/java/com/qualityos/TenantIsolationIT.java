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
        as(tenantB, postJson("/api/v1/invitations", Map.of("email", uniqueEmail(), "roleIds", List.of(roleId))))
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
        addExistingAccount(tenantA, tenantB, "VIEWER");

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
        addExistingAccount(tenantA, tenantB, "VIEWER");
        String body = as(tenantB, postJson("/api/v1/auth/switch-tenant", Map.of(
                        "tenantId", tenantA.tenantId(), "refreshToken", tenantB.refreshToken())))
                .andReturn().getResponse().getContentAsString();
        Session switched = toSession(tenantB.email(), body);

        as(tenantA, postJson("/api/v1/users/" + membershipId(tenantA, tenantB.userId()) + "/deactivate", Map.of()))
                .andExpect(status().isNoContent());

        as(switched, get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void masterDataAndPurchasingAreInvisibleAcrossTenants() throws Exception {
        var fixture = new Object() {
            String supplier;
            String order;
        };
        MasterDataHelper helper = new MasterDataHelper();
        fixture.supplier = helper.supplier(tenantA);
        fixture.order = helper.order(tenantA, fixture.supplier);
        String material = helper.material(tenantA);
        String facility = helper.facility(tenantA);

        for (String path : List.of(
                "/api/v1/suppliers/" + fixture.supplier,
                "/api/v1/purchase-orders/" + fixture.order,
                "/api/v1/materials/" + material,
                "/api/v1/facilities/" + facility)) {
            as(tenantB, get(path)).andExpect(status().isNotFound());
        }
        for (String path : List.of(
                "/api/v1/suppliers", "/api/v1/purchase-orders", "/api/v1/materials", "/api/v1/facilities",
                "/api/v1/material-receipts")) {
            as(tenantB, get(path)).andExpect(jsonPath("$.data.totalItems").value(0));
        }
        as(tenantB, postJson("/api/v1/suppliers/" + fixture.supplier + "/submit", Map.of()))
                .andExpect(status().isNotFound());
        as(tenantB, postJson("/api/v1/purchase-orders/" + fixture.order + "/issue", Map.of()))
                .andExpect(status().isNotFound());
    }

    @Test
    void ordersCannotReferenceAnotherTenantsRecords() throws Exception {
        MasterDataHelper helper = new MasterDataHelper();
        String foreignSupplier = helper.supplier(tenantA);
        String foreignFacility = helper.facility(tenantA);
        String foreignMaterial = helper.material(tenantA);
        String ownSupplier = helper.supplier(tenantB);
        String ownFacility = helper.facility(tenantB);
        String ownMaterial = helper.material(tenantB);

        as(tenantB, postJson("/api/v1/purchase-orders", helper.order(foreignSupplier, ownFacility, ownMaterial)))
                .andExpect(status().isNotFound());
        as(tenantB, postJson("/api/v1/purchase-orders", helper.order(ownSupplier, foreignFacility, ownMaterial)))
                .andExpect(status().isNotFound());
        as(tenantB, postJson("/api/v1/purchase-orders", helper.order(ownSupplier, ownFacility, foreignMaterial)))
                .andExpect(status().isNotFound());
    }

    @Test
    void invitationsAreInvisibleAcrossTenants() throws Exception {
        String email = uniqueEmail();
        invite(tenantA, email, "VIEWER");
        String id = JsonPath.read(as(tenantA, get("/api/v1/invitations")).andReturn().getResponse().getContentAsString(),
                "$.data[0].id");

        as(tenantB, get("/api/v1/invitations")).andExpect(jsonPath("$.data.length()").value(0));
        as(tenantB, postJson("/api/v1/invitations/" + id + "/revoke", Map.of())).andExpect(status().isNotFound());
        as(tenantB, postJson("/api/v1/invitations/" + id + "/resend", Map.of())).andExpect(status().isNotFound());
    }

    @Test
    void anInvitationTokenOnlyWorksUnderItsOwnTenant() throws Exception {
        String token = invite(tenantA, uniqueEmail(), "VIEWER");
        String forged = tenantB.tenantId() + token.substring(token.indexOf('.'));

        mvc.perform(postJson("/api/v1/auth/invitations/preview", Map.of("token", forged)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(postJson("/api/v1/auth/invitations/accept", Map.of(
                        "token", forged, "fullName", "X", "password", PASSWORD)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void notificationsAndPreferencesAreHeldBackByRowLevelSecurity() throws Exception {
        UUID id = UUID.randomUUID();
        asAppRole(tenantA.tenantId(), statement -> statement.update(
                "INSERT INTO notifications (id, tenant_id, user_id, type, title, body, email_status) "
                        + "VALUES (?, ?, ?, 'INSPECTION_SENT_BACK', 'T', 'B', 'PENDING')",
                id, tenantA.tenantId(), tenantA.userId()));

        as(tenantB, get("/api/v1/notifications")).andExpect(jsonPath("$.data.totalItems").value(0));
        as(tenantB, postJson("/api/v1/notifications/" + id + "/read", Map.of())).andExpect(status().isNotFound());
        asAppRole(tenantB.tenantId(), statement -> assertThat(statement.queryForObject(
                "SELECT count(*) FROM notifications WHERE id = ?", Long.class, id)).isZero());
    }

    @Test
    void inspectionsAndTemplatesAreInvisibleAcrossTenants() throws Exception {
        MasterDataHelper helper = new MasterDataHelper();
        String template = JsonPath.read(as(tenantA, postJson("/api/v1/inspection-templates",
                        Map.of("code", helper.unique(), "name", "T", "inspectionType", "IQC")))
                .andReturn().getResponse().getContentAsString(), "$.data.template.id");

        as(tenantB, get("/api/v1/inspection-templates/" + template)).andExpect(status().isNotFound());
        as(tenantB, get("/api/v1/inspection-templates")).andExpect(jsonPath("$.data.totalItems").value(0));
        as(tenantB, get("/api/v1/inspections")).andExpect(jsonPath("$.data.totalItems").value(0));
        as(tenantB, postJson("/api/v1/inspection-templates/" + template + "/publish",
                        Map.of("effectiveFrom", "2026-01-01")))
                .andExpect(status().isNotFound());
        as(tenantB, postJson("/api/v1/inspections",
                        Map.of("receiptItemId", UUID.randomUUID().toString(), "templateId", template)))
                .andExpect(status().isNotFound());
    }

    /** A material from one tenant cannot be attached to another's supplier row, even with raw SQL. */
    @Test
    void compositeForeignKeysHoldForPurchasing() throws Exception {
        MasterDataHelper helper = new MasterDataHelper();
        String foreignSupplier = helper.supplier(tenantA);
        String ownFacility = helper.facility(tenantB);

        assertThatThrownBy(() -> inTransactionAs(tenantB.tenantId(), tenantB.userId(), () -> jdbc.update("""
                INSERT INTO purchase_orders (id, tenant_id, po_number, supplier_id, facility_id, order_date)
                VALUES (?, ?, 'PO-X', ?::uuid, ?::uuid, current_date)
                """, UuidV7.generate(), tenantB.tenantId(), foreignSupplier, ownFacility)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("fk_purchase_orders_supplier_id");
    }

    private class MasterDataHelper {

        private String id(org.springframework.test.web.servlet.ResultActions result) throws Exception {
            return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.id");
        }

        private String unique() {
            return UUID.randomUUID().toString().substring(0, 8);
        }

        String supplier(Session session) throws Exception {
            return id(as(session, postJson("/api/v1/suppliers", Map.of("code", unique(), "name", "S"))));
        }

        String facility(Session session) throws Exception {
            return id(as(session, postJson("/api/v1/facilities", Map.of("code", unique(), "name", "F"))));
        }

        String material(Session session) throws Exception {
            String units = as(session, get("/api/v1/units")).andReturn().getResponse().getContentAsString();
            List<String> kg = JsonPath.read(units, "$.data[?(@.code == 'KG')].id");
            return id(as(session, postJson("/api/v1/materials",
                    Map.of("code", unique(), "name", "M", "uomId", kg.getFirst(), "inspectionRequired", true))));
        }

        Map<String, Object> order(String supplier, String facility, String material) {
            return Map.of(
                    "supplierId", supplier,
                    "facilityId", facility,
                    "orderDate", "2026-11-01",
                    "items", List.of(Map.of("materialId", material, "quantity", "5")));
        }

        String order(Session session, String supplier) throws Exception {
            return id(as(session, postJson("/api/v1/purchase-orders",
                    order(supplier, facility(session), material(session)))));
        }
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
