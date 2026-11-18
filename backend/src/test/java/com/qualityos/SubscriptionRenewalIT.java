package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.subscription.service.SubscriptionService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A plan changed mid-period is charged less what is left of the old one, and a paid period is renewed on its own. */
class SubscriptionRenewalIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/subscription";

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
        makeOperator(admin, "SUPER_ADMIN");
    }

    private String planId(String code) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT id::text FROM subscription_plans WHERE code = ?", String.class, code);
    }

    private BigDecimal monthly(String code) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT monthly_price FROM subscription_plans WHERE code = ?", BigDecimal.class, code);
    }

    private String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private String request(String code) throws Exception {
        return body(as(owner, postJson(BASE + "/plan", Map.of("planId", planId(code), "cycle", "MONTHLY")))
                .andExpect(status().isCreated()));
    }

    private void pay(String invoice) throws Exception {
        String total = asDatabaseSuperuser().queryForObject(
                "SELECT total::text FROM subscription_invoices WHERE id = ?::uuid", String.class, invoice);
        as(admin, postJson("/api/v1/platform/organisations/" + owner.tenantId() + "/invoices/" + invoice + "/payments",
                Map.of("reference", "NEFT-" + UUID.randomUUID(), "amount", total, "failed", false, "method", "NEFT",
                        "paidOn", LocalDate.now().toString())))
                .andExpect(status().isCreated());
    }

    /** Pays for a month of the plan, then moves that month so that {@code usedDays} of its 30 are gone. */
    private String paidFor(String code, int usedDays) throws Exception {
        String invoice = JsonPath.read(request(code), "$.data.id");
        pay(invoice);
        asDatabaseSuperuser().update("""
                UPDATE subscription_invoices SET period_starts_at = now() - make_interval(days => ?),
                    period_ends_at = now() + make_interval(days => ?) WHERE id = ?::uuid""",
                usedDays, 30 - usedDays, invoice);
        asDatabaseSuperuser().update("""
                UPDATE subscriptions SET current_period_ends_at = now() + make_interval(days => ?)
                WHERE tenant_id = ?""", 30 - usedDays, owner.tenantId());
        return asDatabaseSuperuser().queryForObject(
                "SELECT invoice_number FROM subscription_invoices WHERE id = ?::uuid", String.class, invoice);
    }

    private int renew(Session session) {
        return TenantContext.callAs(session.tenantId(), null, () ->
                new TransactionTemplate(transactions).execute(status -> subscriptions.raiseRenewal()));
    }

    private long daysLeft() {
        return asDatabaseSuperuser().queryForObject(
                "SELECT extract(day FROM current_period_ends_at - now())::bigint FROM subscriptions WHERE tenant_id = ?",
                Long.class, owner.tenantId());
    }

    @Test
    void anUpgradeMidPeriodIsChargedLessWhatIsLeftOfTheOldPlan() throws Exception {
        String growth = paidFor("GROWTH", 10);
        String raised = request("PROFESSIONAL");

        BigDecimal credit = new BigDecimal(JsonPath.read(raised, "$.data.prorationCredit").toString());
        BigDecimal expected = monthly("GROWTH").multiply(BigDecimal.valueOf(20))
                .divide(BigDecimal.valueOf(30), 2, RoundingMode.DOWN);
        assertThat(credit).isBetween(expected.subtract(new BigDecimal("0.05")), expected);
        assertThat(new BigDecimal(JsonPath.read(raised, "$.data.amount").toString()))
                .isEqualByComparingTo(monthly("PROFESSIONAL").subtract(credit));
        assertThat(new BigDecimal(JsonPath.read(raised, "$.data.listPrice").toString()))
                .isEqualByComparingTo(monthly("PROFESSIONAL"));
        assertThat((String) JsonPath.read(raised, "$.data.prorationNote")).contains(growth);
        assertThat((String) JsonPath.read(raised, "$.data.status")).isEqualTo("ISSUED");

        String invoice = JsonPath.read(raised, "$.data.id");
        byte[] pdf = as(owner, get(BASE + "/invoices/" + invoice + "/pdf")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        pay(invoice);
        as(owner, get(BASE)).andExpect(jsonPath("$.data.planCode").value("PROFESSIONAL"));
        // The new month starts now: the old one's remainder was paid back as credit, not added on.
        assertThat(daysLeft()).isBetween(27L, 31L);
    }

    @Test
    void aCreditLargerThanTheNewPriceSettlesTheInvoiceAndBecomesExtraDays() throws Exception {
        paidFor("PROFESSIONAL", 3);
        String raised = request("STARTER");

        assertThat((String) JsonPath.read(raised, "$.data.status")).isEqualTo("PAID");
        assertThat(new BigDecimal(JsonPath.read(raised, "$.data.total").toString())).isZero();
        int extraDays = JsonPath.read(raised, "$.data.extraDays");
        // About 9 000 of credit against a price near 3 000: two more months, near enough.
        assertThat(extraDays).isGreaterThan(30);
        as(owner, get(BASE)).andExpect(jsonPath("$.data.planCode").value("STARTER"))
                .andExpect(jsonPath("$.data.state").value("ACTIVE"));
        assertThat(daysLeft()).isGreaterThanOrEqualTo(27L + extraDays);
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND action = 'INVOICE_SETTLED_BY_CREDIT'",
                Integer.class, owner.tenantId())).isEqualTo(1);
        // Nothing was paid, so no payment notice goes out.
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM notifications WHERE tenant_id = ? AND type = 'PAYMENT_RECEIVED'",
                Integer.class, owner.tenantId())).isEqualTo(1);
    }

    @Test
    void renewingTheSamePlanOrATrialIsNotProrated() throws Exception {
        String trial = request("GROWTH");
        assertThat((Object) JsonPath.read(trial, "$.data.prorationCredit")).isNull();
        paidFor("GROWTH", 10);
        String again = request("GROWTH");
        assertThat((Object) JsonPath.read(again, "$.data.prorationCredit")).isNull();
    }

    @Test
    void aPaidPeriodIsRenewedOnItsOwnAWeekBeforeItEnds() throws Exception {
        assertThat(renew(owner)).as("a trial is not renewed").isZero();
        paidFor("GROWTH", 10);
        assertThat(renew(owner)).as("too early").isZero();
        paidFor("GROWTH", 25);
        assertThat(renew(owner)).isEqualTo(1);
        assertThat(renew(owner)).isZero();

        String listed = body(as(owner, get(BASE + "/invoices")));
        assertThat((Boolean) JsonPath.read(listed, "$.data[0].renewal")).isTrue();
        assertThat((String) JsonPath.read(listed, "$.data[0].status")).isEqualTo("ISSUED");
        assertThat(LocalDate.parse(JsonPath.read(listed, "$.data[0].dueOn"))).isAfter(LocalDate.now().plusDays(3));
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM notifications WHERE tenant_id = ? AND type = 'RENEWAL_INVOICE'",
                Integer.class, owner.tenantId())).isPositive();

        pay(JsonPath.read(listed, "$.data[0].id"));
        assertThat(daysLeft()).as("the new month follows the old").isGreaterThan(33L);
        assertThat(renew(owner)).isZero();
    }

    @Test
    void aCancelledSubscriptionIsNotRenewedNorIsAReplacedRenewalRaisedAgain() throws Exception {
        paidFor("GROWTH", 25);
        as(owner, postJson(BASE + "/cancel", Map.of())).andExpect(status().isOk());
        assertThat(renew(owner)).isZero();
        as(owner, postJson(BASE + "/resume", Map.of())).andExpect(status().isOk());
        assertThat(renew(owner)).isEqualTo(1);

        // Choosing another plan replaces the renewal; it is not raised again behind it.
        request("PROFESSIONAL");
        asDatabaseSuperuser().update(
                "UPDATE subscription_invoices SET status = 'VOID' WHERE tenant_id = ? AND status = 'ISSUED'",
                owner.tenantId());
        assertThat(renew(owner)).isZero();
    }
}
