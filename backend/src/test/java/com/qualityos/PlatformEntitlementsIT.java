package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.common.tenant.TenantContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

class PlatformEntitlementsIT extends MasterDataFixture {

    private static final String PLATFORM = "/api/v1/platform";
    private static final String REASON = "Agreed with the customer";

    private Session owner;
    private Session admin;

    @BeforeEach
    void setUp() throws Exception {
        owner = register();
        admin = register();
        asDatabaseSuperuser().update("INSERT INTO platform_admins (user_id) VALUES (?)", admin.userId());
    }

    private static String featureCode() {
        return "F" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private ResultActions createFeature(String code, String type, boolean byDefault) throws Exception {
        return as(admin, postJson(PLATFORM + "/features", Map.of("code", code, "name", "Feature " + code,
                "valueType", type, "defaultEnabled", byDefault)));
    }

    private long featureVersion(String code) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT version FROM feature_definitions WHERE code = ?", Long.class, code);
    }

    private long planVersion(String plan) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT version FROM subscription_plans WHERE id = ?::uuid", Long.class, plan);
    }

    private String newPlan() throws Exception {
        return JsonPath.read(body(as(admin, postJson(PLATFORM + "/plans", Map.of("code", featureCode(), "name", "Pilot")))
                .andExpect(status().isCreated())), "$.data.id");
    }

    private ResultActions assign(String feature, String plan, boolean included, Object value, String reason)
            throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("planId", plan);
        request.put("included", included);
        request.put("value", value);
        request.put("version", planVersion(plan));
        request.put("reason", reason);
        return as(admin, postJson(PLATFORM + "/features/" + feature + "/plans", request));
    }

    /** Moves the organisation to a plan of its own, so that a limit reaches nobody else. */
    private String planWith(Session session, String column, int limit) {
        String plan = featureCode();
        asDatabaseSuperuser().update("""
                INSERT INTO subscription_plans (id, code, name, description, tax_percent, display_order, active, features, %s)
                SELECT gen_random_uuid(), ?, ?, '', tax_percent, 10000, false, features, ? FROM subscription_plans
                WHERE code = 'PROFESSIONAL'
                """.formatted(column), plan, "Metered " + plan, limit);
        asDatabaseSuperuser().update("""
                UPDATE subscriptions SET plan_id = (SELECT id FROM subscription_plans WHERE code = ?) WHERE tenant_id = ?
                """, plan, session.tenantId());
        return plan;
    }

    private ResultActions upload(Session session, String supplier, int bytes) throws Exception {
        byte[] content = new byte[bytes];
        byte[] header = "%PDF-1.4\n".getBytes();
        System.arraycopy(header, 0, content, 0, header.length);
        return mvc.perform(multipart("/api/v1/attachments")
                .file(new MockMultipartFile("file", "note.pdf", "application/pdf", content))
                .param("entityType", "SUPPLIER")
                .param("entityId", supplier)
                .header(HttpHeaders.AUTHORIZATION, session.bearer()));
    }

    private void paidFrom(Session session, String startedAgo, String endsIn, String cycle, String amount) {
        asDatabaseSuperuser().update("""
                INSERT INTO subscription_invoices (id, tenant_id, subscription_id, invoice_number, plan_id, plan_name,
                    billing_cycle, amount, tax_percent, tax_amount, total, status, buyer_name, issued_on, due_on,
                    paid_at, period_starts_at, period_ends_at)
                SELECT gen_random_uuid(), s.tenant_id, s.id, 'T-' || gen_random_uuid(), s.plan_id, 'Analysed', ?, ?::numeric,
                    18, 0, ?::numeric, 'PAID', 'Buyer', current_date, current_date,
                    now() - ?::interval, now() - ?::interval, now() + ?::interval
                FROM subscriptions s WHERE s.tenant_id = ?
                """, cycle, amount, amount, startedAgo, startedAgo, endsIn, session.tenantId());
    }

    private double figure(String path, String query) throws Exception {
        Number value = JsonPath.read(body(as(admin, get(PLATFORM + "/analytics" + query))), path);
        return value == null ? 0 : value.doubleValue();
    }

    @Test
    void anOrdinaryOwnerGetsNoneOfThis() throws Exception {
        for (String path : List.of("/features", "/usage", "/usage/export", "/analytics", "/analytics/export",
                "/features/REPORTS/organisations", "/organisations/" + owner.tenantId() + "/usage/history")) {
            as(owner, get(PLATFORM + path)).andExpect(status().isForbidden());
        }
        as(owner, postJson(PLATFORM + "/features", Map.of("code", featureCode(), "name", "Mine", "valueType", "BOOLEAN",
                "defaultEnabled", false))).andExpect(status().isForbidden());
    }

    @Test
    void theDatabaseItselfRefusesAnOrdinaryMember() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        for (String call : List.of(
                "SELECT * FROM platform_usage_page(NULL, NULL, 10, 0)",
                "SELECT * FROM platform_usage_history(gen_random_uuid(), 30)",
                "SELECT * FROM platform_feature_counts()",
                "SELECT * FROM platform_feature_organisations('REPORTS', 10, 0)",
                "SELECT platform_analytics(now() - interval '1 day', now(), 'UTC')")) {
            assertThatThrownBy(() -> TenantContext.runAs(owner.tenantId(), owner.userId(),
                    () -> tx.executeWithoutResult(status -> jdbc.queryForList(call))))
                    .as(call)
                    .isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> TenantContext.runAs(owner.tenantId(), owner.userId(),
                () -> tx.executeWithoutResult(status -> jdbc.queryForList("SELECT * FROM organisation_usage_now()"))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theCatalogueSaysWhichFeaturesAreActuallyEnforced() throws Exception {
        String custom = featureCode();
        createFeature(custom, "BOOLEAN", false).andExpect(status().isCreated());
        createFeature(custom, "BOOLEAN", false).andExpect(status().isConflict());

        as(admin, get(PLATFORM + "/features"))
                .andExpect(jsonPath("$.data[?(@.code == 'REPORTS')].enforced", hasItem(true)))
                .andExpect(jsonPath("$.data[?(@.code == '" + custom + "')].enforced", hasItem(false)));
    }

    @Test
    void aDefaultFeatureArrivesOnNewPlansOnly() throws Exception {
        String custom = featureCode();
        createFeature(custom, "BOOLEAN", true).andExpect(status().isCreated());
        try {
            String plan = newPlan();
            as(admin, get(PLATFORM + "/plans"))
                    .andExpect(jsonPath("$.data[?(@.id == '" + plan + "')].features[*]", hasItem(custom)))
                    .andExpect(jsonPath("$.data[?(@.code == 'STARTER')].features[*]", not(hasItem(custom))));
        } finally {
            asDatabaseSuperuser().update("UPDATE feature_definitions SET default_enabled = false WHERE code = ?", custom);
        }
    }

    @Test
    void anInactiveFeatureCannotJoinAPlanButStaysOnPlansThatHaveIt() throws Exception {
        String custom = featureCode();
        createFeature(custom, "BOOLEAN", false).andExpect(status().isCreated());
        String has = newPlan();
        String lacks = newPlan();
        assign(custom, has, true, null, null).andExpect(status().isOk());

        as(admin, postJson(PLATFORM + "/features/" + custom + "/active", Map.of("active", false,
                "version", featureVersion(custom)))).andExpect(status().isBadRequest());
        as(admin, postJson(PLATFORM + "/features/" + custom + "/active", Map.of("active", false,
                "version", featureVersion(custom), "reason", REASON))).andExpect(status().isOk());

        assign(custom, lacks, true, null, null).andExpect(status().isUnprocessableContent());
        as(admin, get(PLATFORM + "/plans"))
                .andExpect(jsonPath("$.data[?(@.id == '" + has + "')].features[*]", hasItem(custom)));
    }

    @Test
    void aFeatureValueMustFitItsType() throws Exception {
        String number = featureCode();
        String text = featureCode();
        String structured = featureCode();
        createFeature(number, "NUMBER", false).andExpect(status().isCreated());
        createFeature(text, "TEXT", false).andExpect(status().isCreated());
        createFeature(structured, "STRUCTURED", false).andExpect(status().isCreated());
        String plan = newPlan();

        assign(number, plan, true, "many", null).andExpect(status().isUnprocessableContent());
        assign(number, plan, true, 500, null).andExpect(status().isOk());
        assign(text, plan, true, null, null).andExpect(status().isUnprocessableContent());
        assign(text, plan, true, "Gold", null).andExpect(status().isOk());
        assign(structured, plan, true, "plain", null).andExpect(status().isUnprocessableContent());
        assign(structured, plan, true, Map.of("seats", 3), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.featureValues." + number).value(500))
                .andExpect(jsonPath("$.data.featureValues." + text).value("Gold"))
                .andExpect(jsonPath("$.data.featureValues." + structured + ".seats").value(3));

        assign(number, plan, false, null, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.featureValues." + number).doesNotExist());
    }

    @Test
    void changingAPlanWithSubscribersNeedsAReasonAndIsKeptInItsHistory() throws Exception {
        String custom = featureCode();
        createFeature(custom, "BOOLEAN", false).andExpect(status().isCreated());
        planWith(owner, "max_users", 50);
        String plan = asDatabaseSuperuser().queryForObject(
                "SELECT plan_id::text FROM subscriptions WHERE tenant_id = ?", String.class, owner.tenantId());

        assign(custom, plan, true, null, null).andExpect(status().isUnprocessableContent());
        assign(custom, plan, true, null, REASON).andExpect(status().isOk());

        as(admin, get(PLATFORM + "/plans/" + plan + "/versions"))
                .andExpect(jsonPath("$.data[*].reason", hasItem(REASON)));
        as(admin, get(PLATFORM + "/features/" + custom + "/organisations"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].tenantId").value(owner.tenantId().toString()));
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*)::int FROM audit_logs WHERE action = 'PLAN_FEATURE_CHANGED' AND new_state ->> 'reason' = ?",
                Integer.class, REASON)).isPositive();
    }

    @Test
    void removingAnEnforcedFeatureRestrictsTheOrganisationButNotAnotherRolesRules() throws Exception {
        Session viewer = addMember(owner, "VIEWER");
        planWith(owner, "max_users", 50);
        String plan = asDatabaseSuperuser().queryForObject(
                "SELECT plan_id::text FROM subscriptions WHERE tenant_id = ?", String.class, owner.tenantId());
        as(owner, get("/api/v1/analytics/overview")).andExpect(status().isOk());

        assign("REPORTS", plan, false, null, REASON).andExpect(status().isOk());
        as(owner, get("/api/v1/analytics/overview")).andExpect(status().isForbidden());

        assign("REPORTS", plan, true, null, REASON).andExpect(status().isOk());
        as(owner, get("/api/v1/analytics/overview")).andExpect(status().isOk());
        as(viewer, postJson("/api/v1/facilities", Map.of("code", featureCode(), "name", "Plant", "address", "Pune")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAttachmentLimitIsEnforcedOnUploadAndAFreedPlaceCanBeUsed() throws Exception {
        String supplier = draftSupplier(owner);
        planWith(owner, "max_attachments", 1);

        String id = JsonPath.read(body(upload(owner, supplier, 1024).andExpect(status().isCreated())), "$.data.id");
        upload(owner, supplier, 1024)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message", containsString("1 attachments")));

        as(owner, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/attachments/" + id))
                .andExpect(status().is2xxSuccessful());
        upload(owner, supplier, 1024).andExpect(status().isCreated());
    }

    @Test
    void aFileThatWouldPassTheStorageLimitIsRefusedBeforeItIsStored() throws Exception {
        String supplier = draftSupplier(owner);
        planWith(owner, "max_storage_mb", 1);

        upload(owner, supplier, 700_000).andExpect(status().isCreated());
        upload(owner, supplier, 700_000).andExpect(status().isUnprocessableContent());
        as(owner, get("/api/v1/subscription")).andExpect(jsonPath("$.data.storageBytes").value(700_000));
    }

    @Test
    void twoRequestsForTheLastPlaceCannotBothGetIt() throws Exception {
        String supplier = draftSupplier(owner);
        planWith(owner, "max_attachments", 1);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> uploads = new ArrayList<>();
            for (int attempt = 0; attempt < 4; attempt++) {
                uploads.add(() -> upload(owner, supplier, 1024).andReturn().getResponse().getStatus());
            }
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> upload : pool.invokeAll(uploads)) {
                results.add(upload.get());
            }
            assertThat(results).containsOnlyOnce(201);
        } finally {
            pool.shutdownNow();
        }
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*)::int FROM attachments WHERE tenant_id = ?", Integer.class, owner.tenantId())).isEqualTo(1);
    }

    @Test
    void usageCountsEachOrganisationOnItsOwnAndWarnsNearTheLimit() throws Exception {
        Session other = register();
        facility(other);
        facility(other);
        planWith(owner, "max_users", 5);
        planWith(other, "max_facilities", 2);
        for (int member = 0; member < 3; member++) {
            addMember(owner, "VIEWER");
        }

        as(admin, get(PLATFORM + "/usage?size=100&alert=REACHED&q=" + nameOf(other)))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].facilities").value(2))
                .andExpect(jsonPath("$.data.items[0].level").value("REACHED"));
        as(admin, get(PLATFORM + "/usage?size=100&alert=NEAR&q=" + nameOf(owner)))
                .andExpect(jsonPath("$.data.items[0].users").value(4))
                .andExpect(jsonPath("$.data.items[0].facilities").value(0))
                .andExpect(jsonPath("$.data.items[0].level").value("NEAR"));
        as(admin, get(PLATFORM + "/usage?alert=WRONG")).andExpect(status().isUnprocessableContent());
    }

    private String nameOf(Session session) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT name FROM tenants WHERE id = ?", String.class, session.tenantId());
    }

    @Test
    void anOrganisationNearItsLimitIsPointedAtAPlanThatWouldFit() throws Exception {
        String plan = planWith(owner, "max_users", 1);
        String bigger = featureCode();
        asDatabaseSuperuser().update("""
                INSERT INTO subscription_plans (id, code, name, description, tax_percent, display_order, active, max_users)
                VALUES (gen_random_uuid(), ?, ?, '', 18, 10001, true, 40)
                """, bigger, "Bigger " + bigger);
        try {
            as(admin, get(PLATFORM + "/usage?q=" + nameOf(owner)))
                    .andExpect(jsonPath("$.data.items[0].upgradeTo").value("Bigger " + bigger));
        } finally {
            asDatabaseSuperuser().update("UPDATE subscription_plans SET active = false WHERE code IN (?, ?)", plan, bigger);
        }
    }

    @Test
    void snapshotsCanBeRetakenInADayAndShowInTheHistory() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> jdbc.queryForObject("SELECT record_usage_snapshots()", Integer.class));
        facility(owner);
        tx.executeWithoutResult(status -> jdbc.queryForObject("SELECT record_usage_snapshots()", Integer.class));

        as(admin, get(PLATFORM + "/organisations/" + owner.tenantId() + "/usage/history"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].facilities").value(1));
    }

    @Test
    void theUsageExportNeutralisesFormulasAndLeavesATrace() throws Exception {
        asDatabaseSuperuser().update("UPDATE tenants SET name = ? WHERE id = ?", "=SUM(" + owner.tenantId() + ")",
                owner.tenantId());
        as(admin, get(PLATFORM + "/usage/export?q=" + owner.tenantId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("'=SUM(")));
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*)::int FROM audit_logs WHERE tenant_id = ? AND action = 'USAGE_EXPORTED'",
                Integer.class, admin.tenantId())).isEqualTo(1);
    }

    @Test
    void analyticsAgreeWithTheDashboardAndSpreadAnnualContracts() throws Exception {
        String today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
        String query = "?from=" + today + "&to=" + today;
        double mrr = figure("$.data.mrr", query);
        double arr = figure("$.data.arr", query);

        paidFrom(owner, "1 hour", "11 months", "ANNUAL", "12000");

        assertThat(figure("$.data.mrr", query) - mrr).isEqualTo(1000.0);
        assertThat(figure("$.data.arr", query) - arr).isEqualTo(12000.0);
        Number dashboard = JsonPath.read(body(as(admin, get(PLATFORM + "/dashboard" + query))), "$.data.mrr");
        assertThat(figure("$.data.mrr", query)).isEqualTo(dashboard.doubleValue());
    }

    @Test
    void aLostCustomerAndADowngradeCountAsRevenueChurn() throws Exception {
        Session lost = register();
        Session smaller = register();
        LocalDate start = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(10);
        String query = "?from=" + start + "&to=" + start.plusDays(10);
        double lostCustomers = figure("$.data.lostCustomers", query);
        double revenueLost = figure("$.data.revenueLost", query);

        paidFrom(lost, "20 days", "-1 day", "MONTHLY", "1000");
        paidFrom(smaller, "20 days", "-1 day", "MONTHLY", "1000");
        paidFrom(smaller, "1 day", "20 days", "MONTHLY", "400");

        assertThat(figure("$.data.lostCustomers", query) - lostCustomers).isEqualTo(1.0);
        assertThat(figure("$.data.revenueLost", query) - revenueLost).isEqualTo(1600.0);
    }

    @Test
    void theAnalyticsExportIsAuditedAndAPeriodMustRunForwards() throws Exception {
        as(admin, get(PLATFORM + "/analytics/export"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("MRR (INR)")));
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*)::int FROM audit_logs WHERE tenant_id = ? AND action = 'ANALYTICS_EXPORTED'",
                Integer.class, admin.tenantId())).isEqualTo(1);
        as(admin, get(PLATFORM + "/analytics?from=2026-02-02&to=2026-02-01")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void savingAPlanKeepsItsNewLimits() throws Exception {
        String plan = newPlan();
        Map<String, Object> save = new HashMap<>();
        save.put("name", "Pilot");
        save.put("description", "Pilot plan");
        save.put("taxPercent", 18);
        save.put("maxStorageMb", 2048);
        save.put("maxMonthlyInspections", 100);
        save.put("maxAttachments", 500);
        save.put("features", List.of());
        save.put("trialDays", 14);
        save.put("graceDays", 7);
        save.put("active", false);
        save.put("version", planVersion(plan));
        as(admin, put(PLATFORM + "/plans/" + plan).contentType("application/json").content(json.writeValueAsString(save)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maxStorageMb").value(2048))
                .andExpect(jsonPath("$.data.maxMonthlyInspections").value(100))
                .andExpect(jsonPath("$.data.maxAttachments").value(500));
    }
}
