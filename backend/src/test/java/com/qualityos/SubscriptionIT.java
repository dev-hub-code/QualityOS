package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.subscription.service.SubscriptionService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SubscriptionIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/subscription";
    private static final String PLATFORM = "/api/v1/platform";

    @Autowired
    private SubscriptionService subscriptions;

    @Autowired
    private PlatformTransactionManager transactions;

    private Session owner;
    private Session admin;

    @BeforeEach
    void setUp() throws Exception {
        owner = register();
        admin = register();
        asDatabaseSuperuser().update("INSERT INTO platform_admins (user_id) VALUES (?)", admin.userId());
    }

    private String planId(String code) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT id::text FROM subscription_plans WHERE code = ?", String.class, code);
    }

    private void limitPlan(String code, Integer users, Integer facilities) {
        asDatabaseSuperuser().update("UPDATE subscription_plans SET max_users = ?, max_facilities = ? WHERE code = ?",
                users, facilities, code);
    }

    private void onPlan(Session session, String code) {
        asDatabaseSuperuser().update(
                "UPDATE subscriptions SET plan_id = (SELECT id FROM subscription_plans WHERE code = ?) WHERE tenant_id = ?",
                code, session.tenantId());
    }

    private void endedDaysAgo(Session session, int days) {
        asDatabaseSuperuser().update(
                "UPDATE subscriptions SET current_period_ends_at = now() - make_interval(days => ?) WHERE tenant_id = ?",
                days, session.tenantId());
    }

    private void endsInDays(Session session, double days) {
        asDatabaseSuperuser().update(
                "UPDATE subscriptions SET current_period_ends_at = now() + make_interval(secs => ?) WHERE tenant_id = ?",
                days * 86400, session.tenantId());
    }

    private String body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private Map<String, Object> facilityBody(String code) {
        return Map.of("code", code, "name", code);
    }

    private String request(String planCode, String cycle) throws Exception {
        return JsonPath.read(body(as(owner, postJson(BASE + "/plan", Map.of("planId", planId(planCode), "cycle", cycle)))
                .andExpect(status().isCreated())), "$.data.id");
    }

    private org.springframework.test.web.servlet.ResultActions pay(String invoice, String reference, String amount)
            throws Exception {
        return as(admin, postJson(PLATFORM + "/organisations/" + owner.tenantId() + "/invoices/" + invoice + "/payments",
                Map.of("reference", reference, "amount", amount, "failed", false)));
    }

    private int notices(Session session) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM notifications WHERE tenant_id = ? AND type = 'SUBSCRIPTION_EXPIRY'",
                Integer.class, session.tenantId());
    }

    private int runNotice(Session session) {
        return TenantContext.callAs(session.tenantId(), null, () ->
                new TransactionTemplate(transactions).execute(status -> subscriptions.sendExpiryNotice()));
    }

    @Test
    void aNewOrganisationStartsATrialOfTheFullProduct() throws Exception {
        as(owner, get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planCode").value("PROFESSIONAL"))
                .andExpect(jsonPath("$.data.state").value("TRIAL"))
                .andExpect(jsonPath("$.data.users").value(1));
    }

    @Test
    void pricesComeFromThePlanTableAndAnAdminCanChangeThem() throws Exception {
        String plans = body(as(admin, get(PLATFORM + "/plans")).andExpect(status().isOk()));
        Map<String, Object> starter = JsonPath.<List<Map<String, Object>>>read(plans, "$.data[?(@.code == 'STARTER')]").getFirst();
        assertThat(((Number) starter.get("monthlyPrice")).intValue()).isEqualTo(2999);
        Map<String, Object> edit = new HashMap<>(starter);
        edit.put("monthlyPrice", 3499);
        edit.put("reason", "Price review");
        as(admin, put(PLATFORM + "/plans/" + starter.get("id")).contentType("application/json")
                        .content(json.writeValueAsString(edit)))
                .andExpect(status().isOk());

        as(owner, get(BASE + "/plans")).andExpect(jsonPath("$.data[0].monthlyPrice").value(3499.0));
        String invoice = request("STARTER", "MONTHLY");
        as(owner, get(BASE + "/invoices"))
                .andExpect(jsonPath("$.data[0].id").value(invoice))
                .andExpect(jsonPath("$.data[0].amount").value(3499.0))
                .andExpect(jsonPath("$.data[0].taxAmount").value(629.82))
                .andExpect(jsonPath("$.data[0].total").value(4128.82));
        as(admin, put(PLATFORM + "/plans/" + starter.get("id")).contentType("application/json")
                        .content(json.writeValueAsString(edit)))
                .andExpect(status().isConflict());
    }

    @Test
    void theWebsiteReadsLivePricesWithoutSigningIn() throws Exception {
        mvc.perform(get("/api/v1/public/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("STARTER"))
                .andExpect(jsonPath("$.data[0].monthlyPrice").isNumber())
                .andExpect(jsonPath("$.data[3].monthlyPrice").isEmpty())
                .andExpect(jsonPath("$.data[0].id").doesNotExist())
                .andExpect(jsonPath("$.data[0].graceDays").doesNotExist());
    }

    @Test
    void aPlanNoLongerOfferedLeavesTheWebsite() throws Exception {
        asDatabaseSuperuser().update("UPDATE subscription_plans SET active = FALSE WHERE code = 'GROWTH'");
        try {
            mvc.perform(get("/api/v1/public/plans"))
                    .andExpect(jsonPath("$.data[?(@.code == 'GROWTH')]").isEmpty());
        } finally {
            asDatabaseSuperuser().update("UPDATE subscription_plans SET active = TRUE WHERE code = 'GROWTH'");
        }
    }

    @Test
    void aMemberCannotReachThePlatformConsole() throws Exception {
        as(owner, get(PLATFORM + "/plans")).andExpect(status().isForbidden());
        as(owner, get(PLATFORM + "/organisations")).andExpect(status().isForbidden());
        as(owner, postJson(PLATFORM + "/organisations/" + owner.tenantId() + "/extend", Map.of("days", 30)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theApplicationCannotMakeAPlatformAdministrator() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> asAppRole(owner.tenantId(), sql -> sql.update(
                        "INSERT INTO platform_admins (user_id) VALUES (?)", owner.userId())))
                .hasRootCauseMessage("ERROR: permission denied for table platform_admins");
    }

    @Test
    void onlyTheOwnerManagesBilling() throws Exception {
        Session manager = addMember(owner, "QUALITY_MANAGER");
        as(manager, get(BASE)).andExpect(status().isOk());
        as(manager, get(BASE + "/invoices")).andExpect(status().isForbidden());
        as(manager, postJson(BASE + "/cancel", Map.of())).andExpect(status().isForbidden());
    }

    @Test
    void aPlanChangeTakesEffectOnlyOncePaid() throws Exception {
        String invoice = request("GROWTH", "ANNUAL");
        as(owner, get(BASE)).andExpect(jsonPath("$.data.planCode").value("PROFESSIONAL"));

        pay(invoice, "NEFT-1", "70788.20").andExpect(status().isCreated());

        as(owner, get(BASE))
                .andExpect(jsonPath("$.data.planCode").value("GROWTH"))
                .andExpect(jsonPath("$.data.state").value("ACTIVE"))
                .andExpect(jsonPath("$.data.billingCycle").value("ANNUAL"));
        as(owner, get(BASE + "/payments"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].provider").value("MANUAL"));
        as(owner, get(BASE + "/invoices")).andExpect(jsonPath("$.data[0].status").value("PAID"));
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND action = 'PAYMENT_RECORDED' AND user_id = ?",
                Integer.class, owner.tenantId(), admin.userId())).isEqualTo(1);
    }

    @Test
    void aWrongAmountIsRefusedAndARepeatedReferenceChangesNothing() throws Exception {
        String invoice = request("GROWTH", "MONTHLY");
        pay(invoice, "NEFT-1", "1.00").andExpect(status().isUnprocessableEntity());
        pay(invoice, "NEFT-1", "7078.82").andExpect(status().isCreated());
        pay(invoice, "NEFT-1", "7078.82").andExpect(status().isCreated());
        as(owner, get(BASE + "/payments")).andExpect(jsonPath("$.data.length()").value(1));
        pay(invoice, "NEFT-2", "7078.82").andExpect(status().isConflict());
    }

    @Test
    void aFailedPaymentIsRecordedAndChangesNothingElse() throws Exception {
        String invoice = request("GROWTH", "MONTHLY");
        as(admin, postJson(PLATFORM + "/organisations/" + owner.tenantId() + "/invoices/" + invoice + "/payments",
                        Map.of("reference", "UPI-9", "amount", "7078.82", "failed", true, "failureReason", "Declined")))
                .andExpect(status().isCreated());
        as(owner, get(BASE + "/payments")).andExpect(jsonPath("$.data[0].status").value("FAILED"));
        as(owner, get(BASE)).andExpect(jsonPath("$.data.planCode").value("PROFESSIONAL"));
    }

    @Test
    void aNewRequestVoidsTheOpenInvoice() throws Exception {
        String first = request("GROWTH", "MONTHLY");
        String second = request("STARTER", "MONTHLY");
        as(owner, get(BASE + "/invoices"))
                .andExpect(jsonPath("$.data[0].id").value(second))
                .andExpect(jsonPath("$.data[0].status").value("ISSUED"))
                .andExpect(jsonPath("$.data[1].id").value(first))
                .andExpect(jsonPath("$.data[1].status").value("VOID"));
        pay(first, "NEFT-1", "7078.82").andExpect(status().isConflict());
    }

    @Test
    void anAgreedPlanCannotBeSelfServed() throws Exception {
        as(owner, postJson(BASE + "/plan", Map.of("planId", planId("ENTERPRISE"), "cycle", "ANNUAL")))
                .andExpect(status().isUnprocessableEntity());
        String invoice = JsonPath.read(body(as(admin, postJson(
                        PLATFORM + "/organisations/" + owner.tenantId() + "/invoices",
                        Map.of("planId", planId("ENTERPRISE"), "cycle", "ANNUAL", "amount", "500000")))
                .andExpect(status().isCreated())), "$.data.id");
        pay(invoice, "RTGS-1", "590000.00").andExpect(status().isCreated());
        as(owner, get(BASE))
                .andExpect(jsonPath("$.data.planCode").value("ENTERPRISE"))
                .andExpect(jsonPath("$.data.maxUsers").isEmpty());
    }

    @Test
    void anotherOrganisationsInvoiceDoesNotExist() throws Exception {
        String invoice = request("GROWTH", "MONTHLY");
        Session stranger = register();
        as(stranger, get(BASE + "/invoices")).andExpect(jsonPath("$.data.length()").value(0));
        as(stranger, get(BASE + "/invoices/" + invoice + "/pdf")).andExpect(status().isNotFound());
        as(admin, postJson(PLATFORM + "/organisations/" + stranger.tenantId() + "/invoices/" + invoice + "/payments",
                        Map.of("reference", "X", "amount", "7078.82", "failed", false)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anInvoiceKeepsItsFiguresAndPrints() throws Exception {
        String invoice = request("GROWTH", "MONTHLY");
        asDatabaseSuperuser().update("UPDATE subscription_plans SET name = 'Renamed' WHERE code = 'GROWTH'");
        byte[] pdf = as(owner, get(BASE + "/invoices/" + invoice + "/pdf")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        as(owner, get(BASE + "/invoices")).andExpect(jsonPath("$.data[0].planName").value("Growth"));
        asDatabaseSuperuser().update("UPDATE subscription_plans SET name = 'Growth' WHERE code = 'GROWTH'");
    }

    @Test
    void theUserLimitCountsPendingInvitations() throws Exception {
        limitPlan("STARTER", 2, 1);
        try {
            onPlan(owner, "STARTER");
            as(owner, postJson("/api/v1/invitations", Map.of("email", "a" + System.nanoTime() + "@example.com",
                            "roleIds", List.of(roleId(owner, "VIEWER")))))
                    .andExpect(status().isCreated());
            as(owner, postJson("/api/v1/invitations", Map.of("email", "b" + System.nanoTime() + "@example.com",
                            "roleIds", List.of(roleId(owner, "VIEWER")))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("2 users")));
            as(owner, get("/api/v1/subscription")).andExpect(jsonPath("$.data.users").value(2));
        } finally {
            limitPlan("STARTER", 5, 1);
        }
    }

    @Test
    void theFacilityLimitHolds() throws Exception {
        onPlan(owner, "STARTER");
        as(owner, postJson("/api/v1/facilities", facilityBody("F1"))).andExpect(status().isCreated());
        as(owner, postJson("/api/v1/facilities", facilityBody("F2"))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aDowngradeThatWouldNotFitIsRefused() throws Exception {
        as(owner, postJson("/api/v1/facilities", facilityBody("F1"))).andExpect(status().isCreated());
        as(owner, postJson("/api/v1/facilities", facilityBody("F2"))).andExpect(status().isCreated());
        as(owner, postJson(BASE + "/plan", Map.of("planId", planId("STARTER"), "cycle", "MONTHLY")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aPlanWithoutAFeatureKeepsItsRecordsReadable() throws Exception {
        onPlan(owner, "STARTER");
        as(owner, get("/api/v1/ncrs")).andExpect(status().isOk());
        as(owner, postJson("/api/v1/ncrs", Map.of("title", "T", "description", "D", "severity", "MINOR")))
                .andExpect(status().isForbidden());
        as(owner, get("/api/v1/analytics/overview")).andExpect(status().isForbidden());
        as(owner, get("/api/v1/suppliers")).andExpect(status().isOk());
        as(owner, get("/api/v1/auth/me")).andExpect(jsonPath("$.data.permissions",
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("NCR_CREATE"))));
    }

    @Test
    void theGracePeriodStillWorksThenTheOrganisationTurnsReadOnly() throws Exception {
        endedDaysAgo(owner, 3);
        as(owner, get(BASE)).andExpect(jsonPath("$.data.state").value("GRACE"));
        as(owner, postJson("/api/v1/facilities", facilityBody("F1"))).andExpect(status().isCreated());

        endedDaysAgo(owner, 8);
        as(owner, get(BASE)).andExpect(jsonPath("$.data.state").value("READ_ONLY"));
        as(owner, postJson("/api/v1/facilities", facilityBody("F2"))).andExpect(status().isForbidden());
        as(owner, get("/api/v1/facilities")).andExpect(status().isOk());
        as(owner, get("/api/v1/reports/ncr.xlsx")).andExpect(status().isOk());

        String invoice = request("PROFESSIONAL", "MONTHLY");
        pay(invoice, "NEFT-1", "11798.82").andExpect(status().isCreated());
        as(owner, postJson("/api/v1/facilities", facilityBody("F3"))).andExpect(status().isCreated());
    }

    @Test
    void cancellingRunsToTheEndOfThePeriodAndCanBeUndone() throws Exception {
        as(owner, postJson(BASE + "/cancel", Map.of())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("TRIAL"))
                .andExpect(jsonPath("$.data.cancelledAt").isNotEmpty());
        as(owner, postJson(BASE + "/cancel", Map.of())).andExpect(status().isConflict());
        as(owner, postJson(BASE + "/resume", Map.of())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancelledAt").isEmpty());
    }

    @Test
    void paidEarlyTimeIsAddedToWhatIsLeft() throws Exception {
        String first = request("PROFESSIONAL", "MONTHLY");
        endsInDays(owner, 10);
        pay(first, "NEFT-1", "11798.82").andExpect(status().isCreated());
        Long days = asDatabaseSuperuser().queryForObject(
                "SELECT extract(day FROM current_period_ends_at - now())::bigint FROM subscriptions WHERE tenant_id = ?",
                Long.class, owner.tenantId());
        assertThat(days).isGreaterThan(36);
    }

    @Test
    void anAdminCanGiveMoreTimeAndTheOrganisationSeesThatTheyDid() throws Exception {
        endedDaysAgo(owner, 20);
        as(admin, postJson(PLATFORM + "/organisations/" + owner.tenantId() + "/extend", Map.of("days", 30)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("TRIAL"));
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND action = 'SUBSCRIPTION_EXTENDED'",
                Integer.class, owner.tenantId())).isEqualTo(1);
    }

    @Test
    void theConsoleListsOrganisationsWithoutMixingThem() throws Exception {
        request("GROWTH", "MONTHLY");
        as(admin, get(PLATFORM + "/organisations").param("q", owner.email()))
                .andExpect(jsonPath("$.data.items[0].tenantId").value(owner.tenantId().toString()))
                .andExpect(jsonPath("$.data.items[0].openInvoices.length()").value(1));
        as(admin, get(PLATFORM + "/organisations").param("q", admin.email()))
                .andExpect(jsonPath("$.data.items[0].openInvoices.length()").value(0));
    }

    @Test
    void anOrganisationWaitingOnAPaymentIsListedFirst() throws Exception {
        request("GROWTH", "MONTHLY");
        asDatabaseSuperuser().update(
                "UPDATE subscriptions SET current_period_ends_at = now() + INTERVAL '10 years' WHERE tenant_id = ?",
                owner.tenantId());
        String shared = java.util.UUID.randomUUID().toString();
        asDatabaseSuperuser().update("UPDATE tenants SET name = ? WHERE id IN (?, ?)", shared, owner.tenantId(), admin.tenantId());
        List<String> listed = JsonPath.read(
                body(as(admin, get(PLATFORM + "/organisations").param("q", shared))), "$.data.items[*].tenantId");
        assertThat(listed.indexOf(owner.tenantId().toString())).isLessThan(listed.indexOf(admin.tenantId().toString()));
    }

    @Test
    void expiryNoticesGoOnceAtEachStage() throws Exception {
        endsInDays(owner, 6.5);
        assertThat(runNotice(owner)).isEqualTo(1);
        assertThat(runNotice(owner)).isZero();
        endsInDays(owner, 2.5);
        assertThat(runNotice(owner)).isEqualTo(1);
        endedDaysAgo(owner, 1);
        assertThat(runNotice(owner)).isEqualTo(1);
        endedDaysAgo(owner, 8);
        assertThat(runNotice(owner)).isEqualTo(1);
        assertThat(runNotice(owner)).isZero();
    }

    @Test
    void theJobFindsOnlyOrganisationsThatNeedANotice() throws Exception {
        Session soon = register();
        endsInDays(soon, 3);
        asAppRole(null, sql -> assertThat(sql.queryForList("SELECT tenants_with_subscriptions_ending()", java.util.UUID.class))
                .contains(soon.tenantId()).doesNotContain(owner.tenantId()));
    }
}
