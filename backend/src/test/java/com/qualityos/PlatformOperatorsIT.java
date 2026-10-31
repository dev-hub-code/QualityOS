package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.subscription.service.SubscriptionService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

class PlatformOperatorsIT extends MasterDataFixture {

    private static final String PLATFORM = "/api/v1/platform";
    private static final String REASON = "Joined the operations team";

    @Autowired
    private SubscriptionService subscriptions;

    private Session owner;
    private Session root;

    @BeforeEach
    void setUp() throws Exception {
        owner = register();
        root = register();
        // Earlier tests may leave Super Admins behind, and the last-one rule counts them.
        asDatabaseSuperuser().update("UPDATE platform_admins SET deactivated_at = now()");
        operator(root, "SUPER_ADMIN");
    }

    private void operator(Session session, String role) {
        asDatabaseSuperuser().update("""
                INSERT INTO platform_admins (user_id, role, reauthenticated_until)
                VALUES (?, ?, now() + INTERVAL '1 hour')
                """, session.userId(), role);
    }

    private ResultActions change(Session by, UUID user, String role, boolean active) throws Exception {
        return as(by, put(PLATFORM + "/operators/" + user).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("role", role, "active", active, "reason", REASON))));
    }

    private ResultActions add(Session by, String email, String role) throws Exception {
        return as(by, postJson(PLATFORM + "/operators", Map.of("email", email, "role", role, "reason", REASON)));
    }

    private static String email(Session session) {
        return session.email();
    }

    /** As the job runs: nobody is acting, and people are not told about what they did themselves. */
    private int noticeJob() {
        return TenantContext.callAs(owner.tenantId(), null, () ->
                new TransactionTemplate(transactionManager).execute(status -> subscriptions.sendOverdueNotice()));
    }

    private <T> T inside(Session session, Supplier<T> work) {
        return TenantContext.callAs(session.tenantId(), session.userId(), () ->
                new TransactionTemplate(transactionManager).execute(status -> work.get()));
    }

    @Test
    void aSuperAdminAddsAnOperatorWhoGainsOnlyTheirRole() throws Exception {
        add(root, email(owner), "BILLING_ADMIN").andExpect(status().isNoContent());
        as(owner, get("/api/v1/auth/me")).andExpect(jsonPath("$.data.permissions", hasItem("PLATFORM_BILLING_MANAGE")))
                .andExpect(jsonPath("$.data.permissions", not(hasItem("PLATFORM_OPERATOR_MANAGE"))));
        as(owner, get(PLATFORM + "/audit-logs")).andExpect(status().isForbidden());
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT added_by FROM platform_admins WHERE user_id = ?", UUID.class, owner.userId()))
                .isEqualTo(root.userId());
    }

    @Test
    void anAddressWithNoAccountIsRefusedAndTwiceIsAConflict() throws Exception {
        add(root, "nobody-" + UUID.randomUUID() + "@example.com", "SUPPORT_ADMIN").andExpect(status().isUnprocessableContent());
        add(root, email(owner), "SUPPORT_ADMIN").andExpect(status().isNoContent());
        add(root, email(owner), "BILLING_ADMIN").andExpect(status().isConflict());
    }

    @Test
    void anOrganisationOwnerAndALesserOperatorCannotManageOperators() throws Exception {
        add(owner, email(owner), "SUPER_ADMIN").andExpect(status().isForbidden());
        as(owner, get(PLATFORM + "/operators")).andExpect(status().isForbidden());
        Session billing = register();
        operator(billing, "BILLING_ADMIN");
        add(billing, email(billing), "SUPER_ADMIN").andExpect(status().isForbidden());
    }

    @Test
    void theDatabaseItselfRefusesAnyoneButASuperAdmin() {
        assertThatThrownBy(() -> inside(owner, (() -> jdbc.queryForObject(
                "SELECT platform_operator_add(?, 'SUPER_ADMIN')", String.class, email(owner)))))
                .rootCause().hasMessageContaining("platform role required");
    }

    @Test
    void rolesLimitWhatAnOperatorMayChange() throws Exception {
        Session support = register();
        operator(support, "SUPPORT_ADMIN");
        Session auditor = register();
        operator(auditor, "READ_ONLY_AUDITOR");
        Map<String, Object> plan = Map.of("code", "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(), "name", "Plan");
        as(support, postJson(PLATFORM + "/plans", plan)).andExpect(status().isForbidden());
        as(auditor, postJson(PLATFORM + "/plans", plan)).andExpect(status().isForbidden());
        as(auditor, postJson(PLATFORM + "/organisations/" + owner.tenantId() + "/suspend", Map.of("reason", REASON)))
                .andExpect(status().isForbidden());
        as(auditor, get(PLATFORM + "/audit-logs")).andExpect(status().isOk());
    }

    @Test
    void aDeactivatedOperatorLosesAccessOnTheNextRequestAndAtTheDatabase() throws Exception {
        Session leaving = register();
        operator(leaving, "SUPER_ADMIN");
        as(leaving, get(PLATFORM + "/dashboard")).andExpect(status().isOk());
        change(root, leaving.userId(), "SUPER_ADMIN", false).andExpect(status().isNoContent());
        as(leaving, get(PLATFORM + "/dashboard")).andExpect(status().isForbidden());
        assertThatThrownBy(() -> inside(leaving, (() -> jdbc.queryForObject(
                "SELECT count(*) FROM platform_operators()", Long.class))))
                .rootCause().hasMessageContaining("platform role required");
    }

    @Test
    void theLastSuperAdminCanNeitherLeaveNorBeDemoted() throws Exception {
        change(root, root.userId(), "SUPER_ADMIN", false).andExpect(status().isUnprocessableContent());
        change(root, root.userId(), "BILLING_ADMIN", true).andExpect(status().isUnprocessableContent());
        Session second = register();
        operator(second, "SUPER_ADMIN");
        change(root, root.userId(), "SUPER_ADMIN", false).andExpect(status().isNoContent());
    }

    @Test
    void twoSuperAdminsRemovingEachOtherLeaveOneStanding() throws Exception {
        Session second = register();
        operator(second, "SUPER_ADMIN");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() ->
                    change(root, second.userId(), "SUPER_ADMIN", false).andReturn().getResponse().getStatus());
            Future<Integer> other = pool.submit(() ->
                    change(second, root.userId(), "SUPER_ADMIN", false).andReturn().getResponse().getStatus());
            first.get();
            other.get();
        } finally {
            pool.shutdown();
        }
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM platform_admins WHERE role = 'SUPER_ADMIN' AND deactivated_at IS NULL", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void criticalActionsNeedThePasswordAgainAndTheWindowExpires() throws Exception {
        asDatabaseSuperuser().update("UPDATE platform_admins SET reauthenticated_until = NULL WHERE user_id = ?", root.userId());
        add(root, email(owner), "SUPPORT_ADMIN").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_REQUIRED"));
        as(root, postJson(PLATFORM + "/organisations/" + owner.tenantId() + "/suspend", Map.of("reason", REASON)))
                .andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_REQUIRED"));
        as(root, postJson(PLATFORM + "/reauthenticate", Map.of("password", "not the password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_FAILED"));
        add(root, email(owner), "SUPPORT_ADMIN").andExpect(status().isForbidden());
        as(root, postJson(PLATFORM + "/reauthenticate", Map.of("password", PASSWORD))).andExpect(status().isNoContent());
        add(root, email(owner), "SUPPORT_ADMIN").andExpect(status().isNoContent());
        asDatabaseSuperuser().update(
                "UPDATE platform_admins SET reauthenticated_until = now() - INTERVAL '1 second' WHERE user_id = ?", root.userId());
        change(root, owner.userId(), "BILLING_ADMIN", true).andExpect(status().isForbidden());
    }

    @Test
    void theApplicationRoleCannotExtendItsOwnWindowOrEditTheTrail() {
        assertThatThrownBy(() -> inside(root, (() -> jdbc.update(
                "UPDATE platform_admins SET reauthenticated_until = now() + INTERVAL '1 day'"))))
                .hasRootCauseMessage("ERROR: permission denied for table platform_admins");
        assertThatThrownBy(() -> inside(root, (() -> jdbc.update(
                "DELETE FROM audit_logs WHERE user_id = ?", root.userId()))))
                .hasRootCauseMessage("ERROR: permission denied for table audit_logs");
    }

    @Test
    void theAuditLogShowsOperatorActionsAcrossOrganisationsWithReasons() throws Exception {
        as(root, postJson(PLATFORM + "/organisations/" + owner.tenantId() + "/suspend", Map.of("reason", REASON)))
                .andExpect(status().isNoContent());
        add(root, email(owner), "SUPPORT_ADMIN").andExpect(status().isNoContent());
        String target = owner.tenantId().toString();
        String page = body(as(root, get(PLATFORM + "/audit-logs").param("q", target).param("size", "100"))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(page, "$.data.items[*].reason")).contains(REASON);
        assertThat(JsonPath.<List<String>>read(page, "$.data.items[*].action")).doesNotContain("PLATFORM_ACTION");
        assertThat(JsonPath.<List<String>>read(page, "$.data.items[*].actor")).contains(email(root).toLowerCase());
        as(root, get(PLATFORM + "/audit-logs").param("action", "OPERATOR_ADDED").param("q", email(root)))
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void aCustomersOwnActivityStaysOutOfThePlatformLog() throws Exception {
        as(owner, postJson("/api/v1/facilities", Map.of("name", "Plant " + UUID.randomUUID()))).andReturn();
        as(root, get(PLATFORM + "/audit-logs").param("q", email(owner)))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void sensitiveValuesAreWithheldFromTheLogAndItsExport() throws Exception {
        UUID id = UUID.randomUUID();
        asDatabaseSuperuser().update("""
                INSERT INTO audit_logs (id, tenant_id, user_id, action, entity_type, entity_id, new_state)
                VALUES (?, ?, ?, 'TEST_ACTION', 'Organisation', ?, ?::jsonb)
                """, id, root.tenantId(), root.userId(), id.toString(),
                "{\"reason\":\"ok\",\"buyerGstin\":\"27AAAAA0000A1Z5\",\"nested\":{\"apiToken\":\"abc\"}}");
        as(root, get(PLATFORM + "/audit-logs").param("q", id.toString()))
                .andExpect(jsonPath("$.data.items[0].after.buyerGstin").value("[hidden]"))
                .andExpect(jsonPath("$.data.items[0].after.nested.apiToken").value("[hidden]"));
        as(root, get(PLATFORM + "/audit-logs/export").param("q", id.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("27AAAAA0000A1Z5"))));
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM audit_logs WHERE user_id = ? AND action = 'AUDIT_LOG_EXPORTED'", Long.class, root.userId()))
                .isEqualTo(1L);
    }

    private UUID failedNotification(String status) {
        UUID id = UUID.randomUUID();
        asDatabaseSuperuser().update("""
                INSERT INTO notifications (id, tenant_id, user_id, type, title, body, email_status, email_attempts)
                VALUES (?, ?, ?, 'SUBSCRIPTION_EXPIRY', 'Subscription ending', 'Body', ?, 6)
                """, id, owner.tenantId(), owner.userId(), status);
        asDatabaseSuperuser().update("""
                INSERT INTO email_delivery_logs (id, tenant_id, recipient, template, language, status, attempt,
                                                 error_type, notification_id)
                VALUES (?, ?, ?, 'GENERAL', 'en', 'FAILED', 1, 'MailSendException', ?)
                """, UUID.randomUUID(), owner.tenantId(), id + "@example.com", id);
        return id;
    }

    @Test
    void onlyAGivenUpEmailCanBeRetriedAndItIsAudited() throws Exception {
        UUID failed = failedNotification("FAILED");
        as(root, get(PLATFORM + "/email-logs").param("q", failed.toString()))
                .andExpect(jsonPath("$.data.items[0].retryable").value(true))
                .andExpect(jsonPath("$.data.items[0].errorType").value("MailSendException"));
        String retry = PLATFORM + "/organisations/" + owner.tenantId() + "/notifications/%s/retry-email";
        as(root, postJson(retry.formatted(failed), Map.of("reason", REASON))).andExpect(status().isNoContent());
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT email_status FROM notifications WHERE id = ?", String.class, failed)).isEqualTo("PENDING");
        as(root, postJson(retry.formatted(failed), Map.of("reason", REASON))).andExpect(status().isConflict());
        as(root, postJson(retry.formatted(failedNotification("SENT")), Map.of("reason", REASON)))
                .andExpect(status().isConflict());
        as(root, postJson(retry.formatted(UUID.randomUUID()), Map.of("reason", REASON))).andExpect(status().isNotFound());
    }

    @Test
    void anotherOrganisationsNotificationIsNotFoundUnderTheWrongTenant() throws Exception {
        UUID failed = failedNotification("FAILED");
        as(root, postJson(PLATFORM + "/organisations/" + root.tenantId() + "/notifications/" + failed + "/retry-email",
                Map.of("reason", REASON))).andExpect(status().isNotFound());
    }

    @Test
    void anOverdueInvoiceIsChasedOnceAndAPlanChangeNotifies() throws Exception {
        String starter = asDatabaseSuperuser().queryForObject(
                "SELECT id::text FROM subscription_plans WHERE code = 'STARTER'", String.class);
        String invoice = JsonPath.read(body(as(owner, postJson("/api/v1/subscription/plan",
                Map.of("planId", starter, "cycle", "MONTHLY"))).andExpect(status().isCreated())), "$.data.id");
        asDatabaseSuperuser().update("UPDATE subscription_invoices SET due_on = current_date - 3 WHERE tenant_id = ?", owner.tenantId());
        assertThat(noticeJob()).isEqualTo(1);
        assertThat(noticeJob()).isZero();
        as(root, get(PLATFORM + "/notifications").param("type", "INVOICE_OVERDUE"))
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThan(0)));
        String amount = asDatabaseSuperuser().queryForObject(
                "SELECT total::text FROM subscription_invoices WHERE id = ?::uuid", String.class, invoice);
        as(root, postJson(PLATFORM + "/organisations/" + owner.tenantId() + "/invoices/" + invoice + "/payments", Map.of(
                "reference", "UTR-" + owner.tenantId(), "amount", amount, "failed", false, "method", "UPI",
                "paidOn", LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()))).andExpect(status().is2xxSuccessful());
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM notifications WHERE tenant_id = ? AND type = 'PLAN_CHANGED'", Long.class, owner.tenantId()))
                .isPositive();
        assertThat(noticeJob()).isZero();
    }

    @Test
    void jobsListThoseExpectedAndUnknownOnesAreNotFound() throws Exception {
        as(root, get(PLATFORM + "/jobs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].name", hasItem("notification-delivery")));
        as(root, get(PLATFORM + "/jobs/notification-delivery/runs")).andExpect(status().isOk());
        as(root, get(PLATFORM + "/jobs/no-such-job/runs")).andExpect(status().isNotFound());
        as(owner, get(PLATFORM + "/jobs")).andExpect(status().isForbidden());
    }

    @Test
    void anOrdinaryMemberCannotReadTheEmailLogAtTheDatabase() {
        assertThatThrownBy(() -> inside(owner, (() -> jdbc.queryForObject(
                "SELECT count(*) FROM platform_email_page(NULL, NULL, FALSE, 10, 0)", Long.class))))
                .rootCause().hasMessageContaining("platform administrator required");
    }
}
