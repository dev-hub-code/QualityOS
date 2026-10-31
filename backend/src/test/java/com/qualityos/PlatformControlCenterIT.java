package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.common.tenant.TenantContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PlatformControlCenterIT extends AbstractIntegrationTest {

    private static final String PLATFORM = "/api/v1/platform";

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

    private String body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private static String code() {
        return "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private Map<String, Object> newPlan() throws Exception {
        String created = body(as(admin, postJson(PLATFORM + "/plans", Map.of("code", code(), "name", "Pilot")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.publicVisible").value(false)));
        return JsonPath.read(created, "$.data");
    }

    private String planIdOf(Session session) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT plan_id::text FROM subscriptions WHERE tenant_id = ?", String.class, session.tenantId());
    }

    private long versionOf(String plan) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT version FROM subscription_plans WHERE id = ?::uuid", Long.class, plan);
    }

    /** A paid invoice for the period around now, as settling one would leave it. */
    private void paid(Session session, String cycle, String amount) {
        asDatabaseSuperuser().update("""
                INSERT INTO subscription_invoices (id, tenant_id, subscription_id, invoice_number, plan_id, plan_name,
                    billing_cycle, amount, tax_percent, tax_amount, total, status, buyer_name, issued_on, due_on,
                    paid_at, period_starts_at, period_ends_at)
                SELECT gen_random_uuid(), s.tenant_id, s.id, gen_random_uuid()::text, s.plan_id, 'Test', ?, ?::numeric,
                    18, ?::numeric * 0.18, ?::numeric * 1.18, 'PAID', 'Buyer', current_date, current_date, now(),
                    now() - interval '1 day', now() + interval '20 days'
                FROM subscriptions s WHERE s.tenant_id = ?
                """, cycle, amount, amount, amount, session.tenantId());
    }

    private double dashboardFigure(String path) throws Exception {
        return ((Number) JsonPath.read(body(as(admin, get(PLATFORM + "/dashboard")).andExpect(status().isOk())), path))
                .doubleValue();
    }

    @Test
    void aTenantAdministratorIsRefusedEveryNewPath() throws Exception {
        String plan = planIdOf(owner);
        for (String path : List.of("/dashboard", "/organisations/" + owner.tenantId(), "/organisations/export",
                "/plans/" + plan + "/impact", "/plans/" + plan + "/versions")) {
            as(owner, get(PLATFORM + path)).andExpect(status().isForbidden());
        }
        as(owner, postJson(PLATFORM + "/plans", Map.of("code", code(), "name", "Mine")))
                .andExpect(status().isForbidden());
    }

    @Test
    void theDatabaseRefusesCrossOrganisationReadsEvenIfTheApplicationDidNot() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        for (String call : List.of(
                "SELECT platform_dashboard(now() - interval '1 day', now(), 'UTC')",
                "SELECT * FROM platform_organisation_page(NULL, NULL, NULL, NULL, NULL, NULL, NULL, 10, 0)",
                "SELECT platform_subscriber_count(gen_random_uuid())")) {
            assertThatThrownBy(() -> TenantContext.runAs(owner.tenantId(), owner.userId(),
                    () -> tx.executeWithoutResult(status -> jdbc.queryForList(call))))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void recurringRevenueSpreadsAnAnnualInvoiceAndLeavesOutTax() throws Exception {
        double before = dashboardFigure("$.data.mrr");
        Session monthly = register();
        Session annual = register();
        paid(monthly, "MONTHLY", "1000");
        paid(annual, "ANNUAL", "12000");

        assertThat(dashboardFigure("$.data.mrr") - before).isEqualTo(2000.0);
        assertThat(dashboardFigure("$.data.arr")).isEqualTo(dashboardFigure("$.data.mrr") * 12);
    }

    @Test
    void aCancelledSubscriptionLeavesRecurringRevenueButItsMoneyStaysCollected() throws Exception {
        Session customer = register();
        paid(customer, "MONTHLY", "1000");
        double before = dashboardFigure("$.data.mrr");
        asDatabaseSuperuser().update("UPDATE subscriptions SET cancelled_at = now() WHERE tenant_id = ?",
                customer.tenantId());

        assertThat(before - dashboardFigure("$.data.mrr")).isEqualTo(1000.0);
    }

    @Test
    void aDashboardPeriodMustBeSensible() throws Exception {
        as(admin, get(PLATFORM + "/dashboard?from=2026-02-01&to=2026-01-01")).andExpect(status().isUnprocessableContent());
        as(admin, get(PLATFORM + "/dashboard?from=2020-01-01&to=2026-01-01")).andExpect(status().isUnprocessableContent());
        as(admin, get(PLATFORM + "/dashboard?from=2026-01-15&to=2026-03-02"))
                .andExpect(jsonPath("$.data.months.length()").value(3))
                .andExpect(jsonPath("$.data.months[0].month").value("2026-01"));
    }

    @Test
    void aNewPlanStartsOutOfSaleAndACopyKeepsItsTerms() throws Exception {
        Map<String, Object> plan = newPlan();
        as(owner, get("/api/v1/subscription/plans"))
                .andExpect(jsonPath("$.data[?(@.code == '" + plan.get("code") + "')]").isEmpty());

        as(admin, postJson(PLATFORM + "/plans/" + planIdOf(owner) + "/duplicate",
                        Map.of("code", code(), "name", "Copy")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.maxUsers").isNotEmpty())
                .andExpect(jsonPath("$.data.active").value(false));
        as(admin, postJson(PLATFORM + "/plans", Map.of("code", plan.get("code"), "name", "Again")))
                .andExpect(status().isConflict());
        as(admin, postJson(PLATFORM + "/plans", Map.of("code", "lower case", "name", "Bad")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aPlanInUseNeedsAReasonAndEveryChangeIsKept() throws Exception {
        String id = planIdOf(owner);
        String plans = body(as(admin, get(PLATFORM + "/plans")));
        Map<String, Object> edit = new HashMap<>(JsonPath.<List<Map<String, Object>>>read(
                plans, "$.data[?(@.id == '" + id + "')]").getFirst());
        as(admin, put(PLATFORM + "/plans/" + id).contentType("application/json")
                        .content(json.writeValueAsString(edit)))
                .andExpect(status().isUnprocessableContent());
        as(admin, get(PLATFORM + "/plans/" + id + "/impact"))
                .andExpect(jsonPath("$.data.subscribers").isNotEmpty());

        edit.put("description", "Reviewed " + UUID.randomUUID());
        edit.put("reason", "Annual review");
        as(admin, put(PLATFORM + "/plans/" + id).contentType("application/json")
                        .content(json.writeValueAsString(edit)))
                .andExpect(status().isOk());

        as(admin, get(PLATFORM + "/plans/" + id + "/versions"))
                .andExpect(jsonPath("$.data[0].reason").value("Annual review"))
                .andExpect(jsonPath("$.data[0].changedBy").value(admin.userId().toString()));
        assertThat(asDatabaseSuperuser().queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'PLAN_UPDATED' AND entity_id = ?
                    AND new_state ->> 'reason' = 'Annual review'
                """, Long.class, id)).isEqualTo(1);
    }

    @Test
    void aSaveThatChangesNothingAddsNoHistory() throws Exception {
        String id = (String) newPlan().get("id");
        String plans = body(as(admin, get(PLATFORM + "/plans")));
        Map<String, Object> edit = new HashMap<>(JsonPath.<List<Map<String, Object>>>read(
                plans, "$.data[?(@.id == '" + id + "')]").getFirst());
        edit.put("description", "Pilot terms");
        String save = json.writeValueAsString(edit);
        as(admin, put(PLATFORM + "/plans/" + id).contentType("application/json").content(save))
                .andExpect(status().isOk());
        edit.put("version", versionOf(id));
        as(admin, put(PLATFORM + "/plans/" + id).contentType("application/json")
                        .content(json.writeValueAsString(edit)))
                .andExpect(status().isOk());

        as(admin, get(PLATFORM + "/plans/" + id + "/versions")).andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void theApplicationCannotRewritePlanHistory() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                jdbc.update("UPDATE subscription_plan_versions SET reason = 'x'")))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                jdbc.update("DELETE FROM subscription_plan_versions")))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void aStaleEditIsRefused() throws Exception {
        Map<String, Object> plan = newPlan();
        String id = (String) plan.get("id");
        Map<String, Object> action = Map.of("active", true, "version", versionOf(id), "reason", "Launch");
        as(admin, postJson(PLATFORM + "/plans/" + id + "/active", action)).andExpect(status().isOk());
        as(admin, postJson(PLATFORM + "/plans/" + id + "/active", action)).andExpect(status().isConflict());
        asDatabaseSuperuser().update("UPDATE subscription_plans SET active = FALSE WHERE id = ?::uuid", id);
    }

    @Test
    void aPlanInUseCannotBeArchivedAndAnArchivedPlanCannotChange() throws Exception {
        String used = planIdOf(owner);
        as(admin, postJson(PLATFORM + "/plans/" + used + "/archive",
                        Map.of("version", versionOf(used), "reason", "Old")))
                .andExpect(status().isUnprocessableContent());

        String id = (String) newPlan().get("id");
        as(admin, postJson(PLATFORM + "/plans/" + id + "/archive", Map.of("version", versionOf(id), "reason", "Unused")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.archivedAt").isNotEmpty());
        as(admin, postJson(PLATFORM + "/plans/" + id + "/active",
                        Map.of("active", true, "version", versionOf(id), "reason", "Back")))
                .andExpect(status().isConflict());
    }

    @Test
    void aHiddenPlanLeavesTheWebsiteButStaysOnSale() throws Exception {
        String id = (String) newPlan().get("id");
        asDatabaseSuperuser().update("UPDATE subscription_plans SET active = TRUE, name = 'Hidden deal' WHERE id = ?::uuid", id);
        assertThat(body(mvc.perform(get("/api/v1/public/plans")))).doesNotContain("Hidden deal");
        asDatabaseSuperuser().update("UPDATE subscription_plans SET active = FALSE WHERE id = ?::uuid", id);
    }

    @Test
    void organisationsAreFoundByNameEmailAndGstinAndAPatternIsPlainText() throws Exception {
        String name = asDatabaseSuperuser().queryForObject(
                "SELECT name FROM tenants WHERE id = ?", String.class, owner.tenantId());
        asDatabaseSuperuser().update("UPDATE tenants SET gstin = '27AAAAA0000A1Z5' WHERE id = ?", owner.tenantId());
        for (String search : List.of(name, owner.email(), "27AAAAA0000A1Z5")) {
            as(admin, get(PLATFORM + "/organisations").param("q", search).param("size", "5"))
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.items[0].tenantId").value(owner.tenantId().toString()))
                    .andExpect(jsonPath("$.data.items[0].contactEmail").value(owner.email().toLowerCase()));
        }
        as(admin, get(PLATFORM + "/organisations").param("q", "%")).andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void organisationsPageAndFilterOnTheServer() throws Exception {
        register();
        register();
        as(admin, get(PLATFORM + "/organisations").param("size", "2").param("sort", "NEWEST"))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThan(2)));
        as(admin, get(PLATFORM + "/organisations").param("state", "READ_ONLY").param("q", owner.email()))
                .andExpect(jsonPath("$.data.total").value(0));
        as(admin, get(PLATFORM + "/organisations").param("state", "TRIAL").param("q", owner.email()))
                .andExpect(jsonPath("$.data.total").value(1));
        as(admin, get(PLATFORM + "/organisations").param("state", "NONSENSE")).andExpect(status().isUnprocessableContent());
        as(admin, get(PLATFORM + "/organisations").param("size", "1000")).andExpect(status().is4xxClientError());
    }

    @Test
    void anExportNeutralisesFormulasAndLeavesATrace() throws Exception {
        asDatabaseSuperuser().update("UPDATE tenants SET name = '=HYPERLINK(1)' WHERE id = ?", owner.tenantId());
        as(admin, get(PLATFORM + "/organisations/export").param("q", owner.email()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"'=HYPERLINK(1)\"")));
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'ORGANISATIONS_EXPORTED' AND user_id = ?",
                Long.class, admin.userId())).isEqualTo(1);
    }

    @Test
    void anOrganisationsPageShowsFiguresForThatOrganisationAlone() throws Exception {
        as(admin, get(PLATFORM + "/organisations/" + owner.tenantId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contactEmail").value(owner.email().toLowerCase()))
                .andExpect(jsonPath("$.data.usage.inspections").value(0))
                .andExpect(jsonPath("$.data.subscription.state").value("TRIAL"));
        as(admin, get(PLATFORM + "/organisations/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
