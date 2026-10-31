package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.common.tenant.TenantContext;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

class PlatformConsoleIT extends MasterDataFixture {

    private static final String PLATFORM = "/api/v1/platform";
    private static final String SECRET_NOTE = "Customer is unhappy about renewal price";

    private Session owner;
    private Session root;

    @BeforeEach
    void setUp() throws Exception {
        owner = register();
        root = register();
        operator(root, "SUPER_ADMIN");
    }

    @AfterEach
    void restoreSettings() {
        asDatabaseSuperuser().update("""
                UPDATE platform_settings SET maintenance_mode = FALSE, registration_open = TRUE, default_trial_days = 14,
                                             default_grace_days = 7, support_email = NULL
                """);
        asDatabaseSuperuser().update("UPDATE platform_announcements SET active = FALSE");
    }

    private void operator(Session session, String role) {
        asDatabaseSuperuser().update("""
                INSERT INTO platform_admins (user_id, role, reauthenticated_until)
                VALUES (?, ?, now() + INTERVAL '1 hour')
                """, session.userId(), role);
    }

    private <T> T inside(Session session, Supplier<T> work) {
        return TenantContext.callAs(session.tenantId(), session.userId(), () ->
                new TransactionTemplate(transactionManager).execute(status -> work.get()));
    }

    private ResultActions putJson(Session by, String path, Object body) throws Exception {
        return as(by, put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private long settingsVersion() {
        return asDatabaseSuperuser().queryForObject("SELECT version FROM platform_settings", Long.class);
    }

    private Map<String, Object> settings(boolean maintenance, boolean registration) {
        Map<String, Object> body = new HashMap<>();
        body.put("defaultTrialDays", 21);
        body.put("defaultGraceDays", 5);
        body.put("maintenanceMode", maintenance);
        body.put("registrationOpen", registration);
        body.put("supportEmail", "help@example.com");
        body.put("version", settingsVersion());
        body.put("reason", "Planned change");
        return body;
    }

    private static String today() {
        return LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
    }

    private Map<String, Object> announcement(String title, String audience, String starts, String ends) {
        return new HashMap<>(Map.of("title", title, "message", "Back at 6", "severity", "WARNING", "audience", audience,
                "startsOn", starts, "endsOn", ends, "active", true, "version", 0, "reason", "Planned work"));
    }

    private String openTicket() throws Exception {
        as(root, postJson(PLATFORM + "/support/tickets", Map.of("tenantId", owner.tenantId(), "subject", "Renewal query",
                "note", SECRET_NOTE))).andExpect(status().isNoContent());
        return JsonPath.read(body(as(root, get(PLATFORM + "/support/tickets").param("tenantId", owner.tenantId().toString()))
                .andExpect(status().isOk())), "$.data.items[0].id");
    }

    @Test
    void onlyASuperAdminChangesSettingsAndThePasswordIsNeeded() throws Exception {
        Session support = register();
        operator(support, "SUPPORT_ADMIN");
        putJson(support, PLATFORM + "/settings", settings(false, true)).andExpect(status().isForbidden());
        putJson(owner, PLATFORM + "/settings", settings(false, true)).andExpect(status().isForbidden());
        as(owner, get(PLATFORM + "/settings")).andExpect(status().isForbidden());
        asDatabaseSuperuser().update("UPDATE platform_admins SET reauthenticated_until = NULL WHERE user_id = ?", root.userId());
        putJson(root, PLATFORM + "/settings", settings(false, true)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_REQUIRED"));
    }

    @Test
    void savingOverSomeoneElsesChangeIsAConflictAndItIsAudited() throws Exception {
        Map<String, Object> stale = settings(false, true);
        putJson(root, PLATFORM + "/settings", settings(false, true)).andExpect(status().isOk());
        putJson(root, PLATFORM + "/settings", stale).andExpect(status().isConflict());
        as(root, get(PLATFORM + "/audit-logs").param("action", "SETTINGS_CHANGED").param("q", root.email().toLowerCase()))
                .andExpect(jsonPath("$.data.items[0].reason").value("Planned change"))
                .andExpect(jsonPath("$.data.items[0].organisation").value("Platform"));
    }

    @Test
    void newPlansTakeTheDefaults() throws Exception {
        putJson(root, PLATFORM + "/settings", settings(false, true)).andExpect(status().isOk());
        String code = "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        as(root, postJson(PLATFORM + "/plans", Map.of("code", code, "name", "Pilot"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.trialDays").value(21))
                .andExpect(jsonPath("$.data.graceDays").value(5));
    }

    @Test
    void closingRegistrationRefusesSignUpsButNotSignIns() throws Exception {
        putJson(root, PLATFORM + "/settings", settings(false, false)).andExpect(status().isOk());
        assertThatThrownBy(this::register).isInstanceOf(AssertionError.class);
        as(owner, get("/api/v1/auth/me")).andExpect(status().isOk());
    }

    @Test
    void maintenanceFreezesCustomersButNotOperatorsOrReading() throws Exception {
        putJson(root, PLATFORM + "/settings", settings(true, true)).andExpect(status().isOk());
        as(owner, get("/api/v1/auth/me")).andExpect(jsonPath("$.data.permissions", not(hasItem("FACILITY_MANAGE"))))
                .andExpect(jsonPath("$.data.permissions", hasItem("FACILITY_VIEW")));
        as(owner, postJson("/api/v1/facilities", Map.of("name", "Plant", "code", code()))).andExpect(status().isForbidden());
        as(owner, get("/api/v1/platform-notices")).andExpect(jsonPath("$.data.maintenance").value(true))
                .andExpect(jsonPath("$.data.supportEmail").value("help@example.com"));
        putJson(root, PLATFORM + "/settings", settings(false, true)).andExpect(status().isOk());
        as(owner, get("/api/v1/auth/me")).andExpect(jsonPath("$.data.permissions", hasItem("FACILITY_MANAGE")));
    }

    @Test
    void customersSeeOnlyAnnouncementsForTheirAudienceAndDates() throws Exception {
        String tag = UUID.randomUUID().toString();
        LocalDate day = LocalDate.parse(today());
        as(root, postJson(PLATFORM + "/announcements", announcement(tag + " live", "ALL", today(), today())))
                .andExpect(status().isNoContent());
        as(root, postJson(PLATFORM + "/announcements", announcement(tag + " trial", "TRIAL", today(), today())))
                .andExpect(status().isNoContent());
        as(root, postJson(PLATFORM + "/announcements", announcement(tag + " paid", "PAID", today(), today())))
                .andExpect(status().isNoContent());
        as(root, postJson(PLATFORM + "/announcements",
                announcement(tag + " future", "ALL", day.plusDays(2).toString(), day.plusDays(3).toString())))
                .andExpect(status().isNoContent());
        Map<String, Object> off = announcement(tag + " off", "ALL", today(), today());
        off.put("active", false);
        as(root, postJson(PLATFORM + "/announcements", off)).andExpect(status().isNoContent());
        List<String> titles = JsonPath.read(body(as(owner, get("/api/v1/platform-notices"))), "$.data.announcements[*].title");
        assertThat(titles).contains(tag + " live", tag + " trial")
                .doesNotContain(tag + " paid", tag + " future", tag + " off");
    }

    @Test
    void anAnnouncementCannotEndBeforeItStartsOrBeEditedStale() throws Exception {
        LocalDate day = LocalDate.parse(today());
        as(root, postJson(PLATFORM + "/announcements", announcement("Bad", "ALL", today(), day.minusDays(1).toString())))
                .andExpect(status().isUnprocessableContent());
        as(root, postJson(PLATFORM + "/announcements", announcement("Good " + UUID.randomUUID(), "ALL", today(), today())));
        String id = JsonPath.read(body(as(root, get(PLATFORM + "/announcements"))), "$.data[0].id");
        Map<String, Object> edit = announcement("Edited", "ALL", today(), today());
        edit.put("version", 7);
        putJson(root, PLATFORM + "/announcements/" + id, edit).andExpect(status().isConflict());
        as(owner, postJson(PLATFORM + "/announcements", announcement("No", "ALL", today(), today())))
                .andExpect(status().isForbidden());
    }

    @Test
    void ticketsMoveThroughTheirStatusesAndClosedIsFinal() throws Exception {
        String ticket = openTicket();
        String path = PLATFORM + "/support/tickets/" + ticket;
        as(root, postJson(path + "/notes", Map.of("body", "Called them back"))).andExpect(status().isNoContent());
        as(root, get(path + "/notes")).andExpect(jsonPath("$.data[1].body").value("Called them back"));
        for (String next : List.of("IN_PROGRESS", "WAITING_FOR_CUSTOMER", "RESOLVED", "CLOSED")) {
            long version = asDatabaseSuperuser().queryForObject(
                    "SELECT version FROM support_tickets WHERE id = ?::uuid", Long.class, ticket);
            putJson(root, path + "/status", Map.of("status", next, "version", version)).andExpect(status().isNoContent());
        }
        as(root, postJson(path + "/notes", Map.of("body", "Too late"))).andExpect(status().isUnprocessableContent());
        putJson(root, path + "/status", Map.of("status", "OPEN", "version", 4)).andExpect(status().isUnprocessableContent());
    }

    @Test
    void aStaleStatusChangeIsAConflictAndAnUnknownStatusIsRefused() throws Exception {
        String path = PLATFORM + "/support/tickets/" + openTicket() + "/status";
        putJson(root, path, Map.of("status", "RESOLVED", "version", 9)).andExpect(status().isConflict());
        putJson(root, path, Map.of("status", "DONE", "version", 0)).andExpect(status().isBadRequest());
    }

    @Test
    void notesAreUnreachableByCustomersAndKeptOutOfTheirAuditTrail() throws Exception {
        String ticket = openTicket();
        as(owner, get(PLATFORM + "/support/tickets")).andExpect(status().isForbidden());
        as(owner, get(PLATFORM + "/support/tickets/" + ticket + "/notes")).andExpect(status().isForbidden());
        assertThatThrownBy(() -> inside(owner, () -> jdbc.queryForObject("SELECT count(*) FROM support_notes", Long.class)))
                .rootCause().hasMessageContaining("permission denied for table support_notes");
        assertThatThrownBy(() -> inside(root, () -> jdbc.queryForObject("SELECT count(*) FROM support_tickets", Long.class)))
                .rootCause().hasMessageContaining("permission denied for table support_tickets");
        assertThatThrownBy(() -> inside(owner, () -> jdbc.queryForObject(
                "SELECT count(*) FROM platform_support_notes(?::uuid)", Long.class, ticket)))
                .rootCause().hasMessageContaining("platform administrator required");
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND entity_type = 'SupportTicket'", Long.class,
                owner.tenantId())).isZero();
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM audit_logs WHERE entity_id = ? AND new_state::text LIKE '%unhappy%'", Long.class,
                ticket)).isZero();
    }

    @Test
    void anAuditorReadsTicketsButCannotChangeThem() throws Exception {
        String ticket = openTicket();
        Session auditor = register();
        operator(auditor, "READ_ONLY_AUDITOR");
        as(auditor, get(PLATFORM + "/support/tickets/" + ticket + "/notes")).andExpect(status().isOk());
        as(auditor, postJson(PLATFORM + "/support/tickets/" + ticket + "/notes", Map.of("body", "No")))
                .andExpect(status().isForbidden());
        Session billing = register();
        operator(billing, "BILLING_ADMIN");
        assertThatThrownBy(() -> inside(billing, () -> jdbc.queryForObject(
                "SELECT platform_support_note(?::uuid, 'No')", String.class, ticket)))
                .rootCause().hasMessageContaining("platform role required");
    }

    @Test
    void aTicketForAnUnknownOrganisationIsNotFoundAndErrorsListFailedEmailsOnly() throws Exception {
        as(root, postJson(PLATFORM + "/support/tickets", Map.of("tenantId", UUID.randomUUID(), "subject", "S", "note", "N")))
                .andExpect(status().isNotFound());
        asDatabaseSuperuser().update("""
                INSERT INTO email_delivery_logs (id, tenant_id, recipient, template, language, status, attempt, error_type)
                VALUES (?, ?, 'a@example.com', 'GENERAL', 'en', 'FAILED', 1, 'MailSendException')
                """, UUID.randomUUID(), owner.tenantId());
        as(root, get(PLATFORM + "/support/organisations/" + owner.tenantId() + "/errors"))
                .andExpect(jsonPath("$.data[0].errorType").value("MailSendException"))
                .andExpect(jsonPath("$.data[0].recipient").doesNotExist());
    }
}
