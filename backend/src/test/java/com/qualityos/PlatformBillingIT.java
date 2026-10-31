package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.common.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PlatformBillingIT extends AbstractIntegrationTest {

    private static final String PLATFORM = "/api/v1/platform";
    private static final String REASON = "Requested by the customer";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    private Session owner;
    private Session admin;

    @BeforeEach
    void setUp() throws Exception {
        owner = register();
        admin = register();
        asDatabaseSuperuser().update("INSERT INTO platform_admins (user_id, reauthenticated_until) VALUES (?, now() + INTERVAL '1 hour')", admin.userId());
    }

    private String organisation(Session session) {
        return PLATFORM + "/organisations/" + session.tenantId();
    }

    private ResultActions act(Session session, String action) throws Exception {
        return as(admin, postJson(organisation(session) + action, Map.of("reason", REASON)));
    }

    private String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private String planId(String code) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT id::text FROM subscription_plans WHERE code = ?", String.class, code);
    }

    private String invoice(Session session) throws Exception {
        return JsonPath.read(body(as(admin, postJson(organisation(session) + "/invoices", Map.of(
                        "planId", planId("GROWTH"), "cycle", "MONTHLY", "amount", "1000", "reason", REASON)))
                .andExpect(status().isCreated())), "$.data.id");
    }

    private ResultActions pay(Session session, String invoice, String reference, String amount, LocalDate on)
            throws Exception {
        return as(admin, postJson(organisation(session) + "/invoices/" + invoice + "/payments", Map.of(
                "reference", reference, "amount", amount, "failed", false, "method", "UPI",
                "paidOn", on.toString(), "notes", "Checked against the statement")));
    }

    private static String reference() {
        return "UTR-" + UUID.randomUUID();
    }

    private int audited(Session session, String action) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT count(*)::int FROM audit_logs WHERE tenant_id = ? AND action = ?",
                Integer.class, session.tenantId(), action);
    }

    private String lastReason(Session session, String action) {
        return asDatabaseSuperuser().queryForObject("""
                SELECT new_state ->> 'reason' FROM audit_logs WHERE tenant_id = ? AND action = ?
                ORDER BY occurred_at DESC LIMIT 1
                """, String.class, session.tenantId(), action);
    }

    @Test
    void anOrdinaryOwnerGetsNoneOfThis() throws Exception {
        String own = organisation(owner);
        for (String path : new String[] {"/invoices", "/invoices/export", "/payments", "/trials/summary"}) {
            as(owner, get(PLATFORM + path)).andExpect(status().isForbidden());
        }
        for (String action : new String[] {"/suspend", "/reactivate", "/cancel", "/resume", "/trial/end"}) {
            as(owner, postJson(own + action, Map.of("reason", REASON))).andExpect(status().isForbidden());
        }
    }

    @Test
    void theDatabaseItselfRefusesAnOrdinaryMember() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        for (String call : List.of(
                "SELECT * FROM platform_invoice_page(NULL, NULL, false, NULL, NULL, NULL, NULL, 10, 0)",
                "SELECT * FROM platform_payment_page(NULL, NULL, NULL, NULL, 10, 0)",
                "SELECT platform_trial_summary('Asia/Kolkata')",
                "SELECT platform_reference_used_elsewhere('X', gen_random_uuid())")) {
            assertThatThrownBy(() -> TenantContext.runAs(owner.tenantId(), owner.userId(),
                    () -> tx.executeWithoutResult(status -> jdbc.queryForList(call))))
                    .as(call)
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void everyActionNeedsAReason() throws Exception {
        for (String action : new String[] {"/suspend", "/reactivate", "/cancel", "/resume", "/trial/end"}) {
            as(admin, postJson(organisation(owner) + action, Map.of("reason", " ")))
                    .andExpect(status().isBadRequest());
        }
        as(admin, postJson(organisation(owner) + "/extend", Map.of("days", 5))).andExpect(status().isBadRequest());
    }

    @Test
    void suspendingLocksMembersOutAndReactivatingLetsThemBackWithTheirRecords() throws Exception {
        int auditRows = audited(owner, "ORGANIZATION_CREATED");
        act(owner, "/suspend").andExpect(status().isNoContent());

        as(owner, get("/api/v1/subscription")).andExpect(status().is4xxClientError());
        assertThat(lastReason(owner, "ORGANIZATION_SUSPENDED")).isEqualTo(REASON);
        as(admin, get(organisation(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suspensionReason").value(REASON));
        as(admin, get(PLATFORM + "/organisations?state=SUSPENDED&q=" + owner.email()))
                .andExpect(jsonPath("$.data.total").value(1));
        act(owner, "/suspend").andExpect(status().isConflict());

        act(owner, "/reactivate").andExpect(status().isNoContent());
        as(owner, get("/api/v1/subscription")).andExpect(status().isOk());
        assertThat(audited(owner, "ORGANIZATION_CREATED")).isEqualTo(auditRows);
        assertThat(lastReason(owner, "ORGANIZATION_REACTIVATED")).isEqualTo(REASON);
    }

    @Test
    void anOperatorCannotLockThemselvesOut() throws Exception {
        act(admin, "/suspend").andExpect(status().isUnprocessableContent());
    }

    @Test
    void aSignUpThatExpiredCannotBeBroughtBackAsIfSuspended() throws Exception {
        asDatabaseSuperuser().update("UPDATE tenants SET status = 'SUSPENDED' WHERE id = ?", owner.tenantId());
        act(owner, "/reactivate").andExpect(status().isConflict());
    }

    @Test
    void aSuspendedOrganisationGetsNoRenewalReminders() {
        asDatabaseSuperuser().update("UPDATE tenants SET status = 'SUSPENDED' WHERE id = ?", owner.tenantId());
        assertThat(asDatabaseSuperuser().queryForList("SELECT tenants_with_subscriptions_ending()", UUID.class))
                .doesNotContain(owner.tenantId());
    }

    @Test
    void aTrialCanBeExtendedAndEndedWithTheReasonKept() throws Exception {
        as(admin, postJson(organisation(owner) + "/extend", Map.of("days", 5, "reason", REASON)))
                .andExpect(status().isOk());
        assertThat(lastReason(owner, "PLATFORM_ACTION")).isEqualTo(REASON);
        assertThat(audited(owner, "TRIAL_EXTENDED")).isEqualTo(1);

        act(owner, "/trial/end").andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("GRACE"));
        assertThat(audited(owner, "TRIAL_ENDED")).isEqualTo(1);
        act(owner, "/trial/end").andExpect(status().isConflict());
    }

    @Test
    void cancelAndResumeUseTheExistingLifecycle() throws Exception {
        act(owner, "/cancel").andExpect(status().isOk()).andExpect(jsonPath("$.data.cancelledAt").isNotEmpty());
        as(admin, get(PLATFORM + "/organisations?state=CANCELLED&q=" + owner.email()))
                .andExpect(jsonPath("$.data.total").value(1));
        act(owner, "/cancel").andExpect(status().isConflict());
        act(owner, "/resume").andExpect(status().isOk()).andExpect(jsonPath("$.data.cancelledAt").isEmpty());
        assertThat(audited(owner, "SUBSCRIPTION_CANCELLED")).isEqualTo(1);
    }

    @Test
    void anInvoiceWithNoAmountUsesTheListPrice() throws Exception {
        String id = JsonPath.read(body(as(admin, postJson(organisation(owner) + "/invoices", Map.of(
                        "planId", planId("GROWTH"), "cycle", "MONTHLY", "reason", REASON)))
                .andExpect(status().isCreated())), "$.data.id");
        as(admin, get(organisation(owner) + "/invoices/" + id))
                .andExpect(jsonPath("$.data.amount").value(5999.0))
                .andExpect(jsonPath("$.data.taxTreatment").value("UNDETERMINED"));
    }

    @Test
    void invoicesAreListedAcrossOrganisationsWithWhatIsStillOwed() throws Exception {
        Session other = register();
        String open = invoice(owner);
        String settled = invoice(other);
        pay(other, settled, reference(), "1180.00", LocalDate.now().minusDays(1)).andExpect(status().isCreated());

        String number = asDatabaseSuperuser().queryForObject(
                "SELECT invoice_number FROM subscription_invoices WHERE id = ?::uuid", String.class, open);
        as(admin, get(PLATFORM + "/invoices?status=ISSUED&min=1180&max=1180&size=100&q=" + owner.email()))
                .andExpect(status().isOk());
        as(admin, get(PLATFORM + "/invoices?status=PAID&min=1180&max=1180&size=100"))
                .andExpect(jsonPath("$.data.items[?(@.id == '" + settled + "')].outstanding", hasItem(0)))
                .andExpect(jsonPath("$.data.items[?(@.id == '" + settled + "')].paid", hasItem(1180.0)));
        as(admin, get(PLATFORM + "/invoices?status=ISSUED&size=100&q=" + number))
                .andExpect(jsonPath("$.data.items[0].id").value(open))
                .andExpect(jsonPath("$.data.items[0].outstanding").value(1180.0));
        as(admin, get(PLATFORM + "/invoices?status=WRONG")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void overdueMeansOpenAndPastItsDueDate() throws Exception {
        String id = invoice(owner);
        as(admin, get(PLATFORM + "/invoices?overdue=true&size=100"))
                .andExpect(jsonPath("$.data.items[*].id", not(hasItem(id))));
        asDatabaseSuperuser().update("UPDATE subscription_invoices SET due_on = current_date - 1 WHERE id = ?::uuid", id);
        as(admin, get(PLATFORM + "/invoices?overdue=true&size=100"))
                .andExpect(jsonPath("$.data.items[*].id", hasItem(id)));
    }

    @Test
    void theExportNeutralisesFormulasAndLeavesATrace() throws Exception {
        invoice(owner);
        asDatabaseSuperuser().update("UPDATE tenants SET name = ? WHERE id = ?", "=HYPERLINK(1)", owner.tenantId());
        as(admin, get(PLATFORM + "/invoices/export?q=HYPERLINK"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("'=HYPERLINK(1)")));
        assertThat(audited(admin, "INVOICES_EXPORTED")).isEqualTo(1);
    }

    @Test
    void theInvoicePdfNeedsTheRightOrganisation() throws Exception {
        Session other = register();
        String id = invoice(owner);
        as(admin, get(organisation(owner) + "/invoices/" + id + "/pdf")).andExpect(status().isOk());
        as(admin, get(organisation(other) + "/invoices/" + id + "/pdf")).andExpect(status().isNotFound());
        as(admin, get(organisation(other) + "/invoices/" + id)).andExpect(status().isNotFound());
        as(owner, get(organisation(owner) + "/invoices/" + id + "/pdf")).andExpect(status().isForbidden());
    }

    @Test
    void aPaymentKeepsItsDetailsAndWhoRecordedIt() throws Exception {
        String utr = reference();
        LocalDate on = LocalDate.now().minusDays(2);
        pay(owner, invoice(owner), utr, "1180.00", on).andExpect(status().isCreated());

        as(admin, get(PLATFORM + "/payments?q=" + utr.toLowerCase()))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].method").value("UPI"))
                .andExpect(jsonPath("$.data.items[0].paidOn").value(on.toString()))
                .andExpect(jsonPath("$.data.items[0].notes").value("Checked against the statement"))
                .andExpect(jsonPath("$.data.items[0].recordedBy").value(admin.email().toLowerCase()));
        as(admin, get(PLATFORM + "/payments?q=" + utr + "&from=" + LocalDate.now()))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void aPaymentMustMatchAndCannotBeDatedAhead() throws Exception {
        String id = invoice(owner);
        pay(owner, id, reference(), "100.00", LocalDate.now().minusDays(1)).andExpect(status().isUnprocessableContent());
        pay(owner, id, reference(), "1180.00", LocalDate.now().plusDays(2)).andExpect(status().isUnprocessableContent());
        as(admin, get(organisation(owner) + "/invoices/" + id)).andExpect(jsonPath("$.data.status").value("ISSUED"));
    }

    @Test
    void aReferenceCannotPayTwoInvoicesAnywhereHoweverItIsTyped() throws Exception {
        Session other = register();
        String utr = reference();
        String id = invoice(owner);
        LocalDate on = LocalDate.now().minusDays(1);
        pay(owner, id, utr, "1180.00", on).andExpect(status().isCreated());

        pay(owner, id, utr, "1180.00", on).andExpect(status().isCreated());
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*)::int FROM payment_transactions WHERE provider_reference = ?", Integer.class, utr))
                .isEqualTo(1);
        pay(other, invoice(other), utr.toLowerCase(), "1180.00", on).andExpect(status().isConflict());
        pay(owner, invoice(owner), utr, "1180.00", on).andExpect(status().isConflict());
    }

    @Test
    void trialFiguresCountWhatIsEndingAndWhatConverted() throws Exception {
        long before = ((Number) JsonPath.read(body(as(admin, get(PLATFORM + "/trials/summary"))), "$.data.convertedThisMonth"))
                .longValue();
        asDatabaseSuperuser().update(
                "UPDATE subscriptions SET current_period_ends_at = now() + INTERVAL '2 days' WHERE tenant_id = ?",
                owner.tenantId());
        as(admin, get(PLATFORM + "/trials/summary"))
                .andExpect(jsonPath("$.data.endingIn3Days").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.endingIn7Days").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        pay(owner, invoice(owner), reference(), "1180.00", LocalDate.now().minusDays(1)).andExpect(status().isCreated());
        as(admin, get(PLATFORM + "/trials/summary"))
                .andExpect(jsonPath("$.data.convertedThisMonth").value((int) before + 1));
    }
}
