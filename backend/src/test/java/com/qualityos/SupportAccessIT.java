package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class SupportAccessIT extends MasterDataFixture {

    private Session owner;
    private Session operator;
    private String ncr;

    @BeforeEach
    void setUp() throws Exception {
        owner = register();
        operator = register();
        makeOperator(operator, "SUPPORT_ADMIN");
        ncr = idOf(body(as(owner, postJson("/api/v1/ncrs", Map.of("title", "Cracked weld",
                "description", "Found at final check", "severity", "MAJOR"))).andExpect(status().isCreated())));
    }

    private ResultActions grant(Session session, int hours) throws Exception {
        return as(session, postJson("/api/v1/support-access", Map.of("durationHours", hours, "note", "Ticket 42")));
    }

    private ResultActions start(Session session, UUID tenant) throws Exception {
        return as(session, postJson("/api/v1/platform/organisations/" + tenant + "/support-session",
                Map.of("reason", "Customer asked why NCR numbering skipped")));
    }

    private String supportToken() throws Exception {
        return JsonPath.read(body(start(operator, owner.tenantId()).andExpect(status().isOk())), "$.data.accessToken");
    }

    private ResultActions withToken(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    @Test
    void nothingIsReadableUntilTheCustomerGrantsAccess() throws Exception {
        start(operator, owner.tenantId())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message")
                        .value("This organisation has not granted support access, or its access has ended."));
        as(operator, get("/api/v1/platform/organisations/" + owner.tenantId() + "/support-access"))
                .andExpect(jsonPath("$.data.openUntil").isEmpty());
        // Only administrators decide, and only for the lengths offered.
        Session viewer = addMember(owner, "VIEWER");
        grant(viewer, 24).andExpect(status().isForbidden());
        grant(owner, 5).andExpect(jsonPath("$.error.message").value("Choose 24 hours, 3 days or 7 days."));
    }

    @Test
    void aGrantedOperatorReadsButCannotChangeAndEveryReadIsLogged() throws Exception {
        grant(owner, 72).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current.open").value(true))
                .andExpect(jsonPath("$.data.current.note").value("Ticket 42"));
        as(operator, get("/api/v1/platform/organisations/" + owner.tenantId() + "/support-access"))
                .andExpect(jsonPath("$.data.openUntil").isNotEmpty());

        String token = supportToken();
        withToken(token, get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantId").value(owner.tenantId().toString()))
                .andExpect(jsonPath("$.data.support.organizationName").isNotEmpty())
                .andExpect(jsonPath("$.data.permissions").value(org.hamcrest.Matchers.hasItem("NCR_VIEW")))
                .andExpect(jsonPath("$.data.permissions").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("NCR_CREATE"))))
                .andExpect(jsonPath("$.data.permissions").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("PLATFORM_ADMIN"))));
        withToken(token, get("/api/v1/ncrs/" + ncr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Cracked weld"));

        // Read-only, whatever the endpoint would allow a member, and no way into the Control Center.
        withToken(token, postJson("/api/v1/ncrs", Map.of("title", "X", "description", "Y", "severity", "MINOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.message").value("Support access is read-only."));
        withToken(token, postJson("/api/v1/notifications/read-all", Map.of()))
                .andExpect(status().isForbidden());
        withToken(token, get("/api/v1/support-access")).andExpect(status().isForbidden());
        withToken(token, get("/api/v1/platform/organisations")).andExpect(status().isForbidden());

        // The customer sees why support came in and what it read.
        String overview = body(as(owner, get("/api/v1/support-access")).andExpect(status().isOk()));
        List<String> kinds = JsonPath.read(overview, "$.data.events[*].kind");
        List<String> details = JsonPath.read(overview, "$.data.events[*].detail");
        assertThat(kinds).contains("STARTED", "VIEWED");
        assertThat(details).contains("Customer asked why NCR numbering skipped", "/ncrs/" + ncr);
        assertThat(details).noneMatch(detail -> detail.startsWith("/auth/"));
        assertThat(JsonPath.<List<String>>read(overview, "$.data.events[*].operatorName")).doesNotContainNull();
        as(owner, get("/api/v1/audit-logs").param("action", "SUPPORT_SESSION_STARTED"))
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    private ResultActions grantChanges(int hours) throws Exception {
        return as(owner, postJson("/api/v1/support-access",
                Map.of("durationHours", hours, "note", "Fix the batch numbers", "allowChanges", true)));
    }

    @Test
    void changesAreGrantedOnPurposeAndForADayAtMost() throws Exception {
        grantChanges(72).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value("Support can be let make changes for at most 24 hours."));
        grant(owner, 72).andExpect(jsonPath("$.data.current.allowChanges").value(false));
        as(operator, get("/api/v1/platform/organisations/" + owner.tenantId() + "/support-access"))
                .andExpect(jsonPath("$.data.allowChanges").value(false));

        grantChanges(24).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current.allowChanges").value(true));
        as(operator, get("/api/v1/platform/organisations/" + owner.tenantId() + "/support-access"))
                .andExpect(jsonPath("$.data.allowChanges").value(true));
        // The database holds the limit too.
        assertThat(asDatabaseSuperuser().queryForObject("""
                SELECT count(*) FROM support_access_grants
                WHERE allow_changes AND expires_at > granted_at + INTERVAL '24 hours'
                """, Integer.class)).isZero();
    }

    @Test
    void supportMakesChangesAsSupportButNeverDecidesOrManagesAccess() throws Exception {
        grantChanges(24).andExpect(status().isOk());
        String token = supportToken();
        String operatorName = JsonPath.read(body(as(operator, get("/api/v1/auth/me"))), "$.data.user.fullName");

        String me = body(withToken(token, get("/api/v1/auth/me")).andExpect(status().isOk()));
        assertThat(JsonPath.<Boolean>read(me, "$.data.support.allowChanges")).isTrue();
        List<String> permissions = JsonPath.read(me, "$.data.permissions");
        assertThat(permissions).contains("NCR_CREATE", "NCR_VIEW")
                .doesNotContain("NCR_CLOSE", "CAPA_APPROVE", "INSPECTION_APPROVE", "DISPOSITION_RECORD",
                        "USER_MANAGE", "ROLE_MANAGE", "TENANT_MANAGE", "SUBSCRIPTION_MANAGE", "PRODUCTION_COST_VIEW",
                        "PLATFORM_ADMIN");

        // An ordinary change goes through, and is support's in the audit trail, never a member's.
        String made = idOf(body(withToken(token, postJson("/api/v1/ncrs", Map.of("title", "Wrong batch number",
                "description", "Entered for the customer", "severity", "MINOR"))).andExpect(status().isCreated())));
        String audit = body(as(owner, get("/api/v1/audit-logs").param("q", made)).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Boolean>>read(audit, "$.data.items[*].bySupport")).isNotEmpty().containsOnly(true);
        assertThat(JsonPath.<List<String>>read(audit, "$.data.items[*].actor"))
                .containsOnly("Support (" + operatorName + ")");
        // Nor do records the members did themselves get marked.
        String own = body(as(owner, get("/api/v1/audit-logs").param("q", ncr)));
        assertThat(JsonPath.<List<Boolean>>read(own, "$.data.items[*].bySupport")).containsOnly(false);

        // Decisions, access, billing and support access stay with the organisation.
        withToken(token, postJson("/api/v1/ncrs/" + ncr + "/close", Map.of("note", "Closed by support")))
                .andExpect(status().isForbidden());
        withToken(token, postJson("/api/v1/invitations", Map.of("email", "x@example.com", "roleIds", List.of())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.message")
                        .value("Support cannot change this, even with access to make changes."));
        withToken(token, postJson("/api/v1/support-access", Map.of("durationHours", 168)))
                .andExpect(status().isForbidden());

        // The administrators were told, and the support log lists the change.
        String inbox = body(as(owner, get("/api/v1/notifications")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(inbox, "$.data.items[*].type")).contains("SUPPORT_CHANGES_STARTED");
        String overview = body(as(owner, get("/api/v1/support-access")));
        assertThat(JsonPath.<List<String>>read(overview, "$.data.events[?(@.kind == 'CHANGED')].detail"))
                .containsExactly("POST /ncrs");
    }

    @Test
    void aReadOnlySessionTellsNoOne() throws Exception {
        grant(owner, 24).andExpect(status().isOk());
        String token = supportToken();
        withToken(token, get("/api/v1/auth/me")).andExpect(jsonPath("$.data.support.allowChanges").value(false));
        String inbox = body(as(owner, get("/api/v1/notifications")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(inbox, "$.data.items[*].type"))
                .doesNotContain("SUPPORT_CHANGES_STARTED");
    }

    @Test
    void withdrawingAccessEndsASessionAlreadyOpen() throws Exception {
        grant(owner, 24).andExpect(status().isOk());
        String token = supportToken();
        withToken(token, get("/api/v1/ncrs/" + ncr)).andExpect(status().isOk());

        as(owner, postJson("/api/v1/support-access/revoke", Map.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current").isEmpty())
                .andExpect(jsonPath("$.data.grants[0].revokedAt").isNotEmpty());
        withToken(token, get("/api/v1/ncrs/" + ncr)).andExpect(status().isUnauthorized());
        start(operator, owner.tenantId()).andExpect(status().isUnprocessableEntity());
        as(owner, postJson("/api/v1/support-access/revoke", Map.of()))
                .andExpect(jsonPath("$.error.message").value("Support has no access to withdraw."));
    }

    @Test
    void anExpiredGrantEndsBySelf() throws Exception {
        grant(owner, 24).andExpect(status().isOk());
        String token = supportToken();
        asDatabaseSuperuser().update("""
                UPDATE support_access_grants SET granted_at = now() - INTERVAL '2 days', expires_at = now() - INTERVAL '1 second'
                WHERE tenant_id = ?
                """, owner.tenantId());
        withToken(token, get("/api/v1/ncrs/" + ncr)).andExpect(status().isUnauthorized());
        as(owner, get("/api/v1/support-access")).andExpect(jsonPath("$.data.current").isEmpty());
    }

    @Test
    void onlyAnOperatorWhoHandlesCustomersMayOpenASessionAndOnlyWhereGranted() throws Exception {
        grant(owner, 24).andExpect(status().isOk());
        Session billing = register();
        makeOperator(billing, "BILLING_ADMIN");
        start(billing, owner.tenantId()).andExpect(status().isForbidden());
        // A customer's own member is no operator at all.
        start(owner, owner.tenantId()).andExpect(status().isForbidden());

        Session elsewhere = register();
        start(operator, elsewhere.tenantId()).andExpect(status().isUnprocessableEntity());

        // A session in one organisation reads nothing of another, even one that also granted access.
        grant(elsewhere, 24).andExpect(status().isOk());
        String token = supportToken();
        withToken(token, get("/api/v1/ncrs")).andExpect(jsonPath("$.data.totalItems").value(1));

        // An operator who no longer handles customers loses the session at once.
        asDatabaseSuperuser().update("UPDATE platform_admins SET role = 'READ_ONLY_AUDITOR' WHERE user_id = ?",
                operator.userId());
        withToken(token, get("/api/v1/ncrs")).andExpect(status().isUnauthorized());
    }

    @Test
    void grantingAgainReplacesTheGrantInForce() throws Exception {
        grant(owner, 168).andExpect(status().isOk());
        String body = body(grant(owner, 24).andExpect(status().isOk()));
        List<Boolean> open = JsonPath.read(body, "$.data.grants[*].open");
        assertThat(open).containsExactly(true, false);
        as(owner, get("/api/v1/audit-logs").param("action", "SUPPORT_ACCESS_GRANTED"))
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }
}
