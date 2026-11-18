package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.quality.service.CapaService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

class QualityIT extends AbstractIntegrationTest {

    private static final String FUTURE = LocalDate.now().plusDays(14).toString();

    @Autowired
    private CapaService capaService;

    private Session owner;
    private Session manager;

    @BeforeEach
    void organisation() throws Exception {
        owner = register();
        manager = addMember(owner, "QUALITY_MANAGER");
    }

    private String read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
    }

    private String raise(Session session, String severity) throws Exception {
        return read(as(session, postJson("/api/v1/ncrs", Map.of(
                        "title", "Burrs on edge", "description", "Found at line 2", "severity", severity,
                        "defectCategoryId", category(session))))
                .andExpect(status().isCreated()), "$.data.id");
    }

    private String category(Session session) throws Exception {
        return read(as(session, postJson("/api/v1/defect-categories",
                        Map.of("code", "C" + UUID.randomUUID(), "name", "Surface")))
                .andExpect(status().isCreated()), "$.data.id");
    }

    private ResultActions act(Session session, String path, Object body) throws Exception {
        return as(session, postJson("/api/v1" + path, body));
    }

    /** Brings an NCR to where it needs a corrective action. */
    private String ncrAwaitingAction(String severity) throws Exception {
        String ncr = raise(owner, severity);
        act(owner, "/ncrs/" + ncr + "/assign", Map.of("assigneeId", manager.userId()))
                .andExpect(jsonPath("$.data.status").value("UNDER_INVESTIGATION"));
        act(owner, "/ncrs/" + ncr + "/containment", Map.of("containmentAction", "Quarantined lot"))
                .andExpect(jsonPath("$.data.status").value("CONTAINMENT"));
        act(owner, "/ncrs/" + ncr + "/investigation", Map.of("rootCause", "Worn tool"))
                .andExpect(jsonPath("$.data.status").value("ACTION_REQUIRED"));
        return ncr;
    }

    private Map<String, Object> capaRequest(String ncr, Session capaOwner, String target) {
        Map<String, Object> request = new HashMap<>(Map.of(
                "title", "Tool wear", "problemStatement", "Burrs from worn tool", "ownerId", capaOwner.userId(),
                "targetDate", target, "effectivenessRequired", false));
        request.put("ncrId", ncr);
        return request;
    }

    private String capa(String ncr, Session capaOwner) throws Exception {
        return read(act(owner, "/capas", capaRequest(ncr, capaOwner, FUTURE)).andExpect(status().isCreated()), "$.data.id");
    }

    private void analyse(String capa) throws Exception {
        act(owner, "/capas/" + capa + "/start-analysis", Map.of())
                .andExpect(jsonPath("$.data.status").value("ROOT_CAUSE_ANALYSIS"));
        as(owner, put("/api/v1/capas/" + capa + "/analysis").contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "rootCause", "No tool-life limit",
                                "fiveWhys", List.of("Burrs", "Worn tool", "No limit set"),
                                "fishbone", Map.of("Machine", List.of("Worn insert"))))))
                .andExpect(jsonPath("$.data.fiveWhys[2]").value("No limit set"))
                .andExpect(jsonPath("$.data.fishbone.Machine[0]").value("Worn insert"));
        act(owner, "/capas/" + capa + "/finish-analysis", Map.of())
                .andExpect(jsonPath("$.data.status").value("ACTION_PLANNING"));
    }

    private String addAction(String capa, String type) throws Exception {
        String body = act(owner, "/capas/" + capa + "/actions", Map.of(
                        "actionType", type, "description", "Set tool-life limit", "ownerId", manager.userId(),
                        "targetDate", FUTURE))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.actions[-1].id");
    }

    private void implement(String capa, String action) throws Exception {
        act(owner, "/capas/" + capa + "/start-implementation", Map.of())
                .andExpect(jsonPath("$.data.status").value("IMPLEMENTATION"));
        act(owner, "/capas/" + capa + "/actions/" + action + "/complete", Map.of("note", "Limit in the control plan"))
                .andExpect(status().isOk());
        act(owner, "/capas/" + capa + "/submit-verification", Map.of())
                .andExpect(jsonPath("$.data.status").value("EFFECTIVENESS_VERIFICATION"));
    }

    private ResultActions verify(Session session, String capa, boolean effective) throws Exception {
        return act(session, "/capas/" + capa + "/verify",
                Map.of("effective", effective, "method", "Next 3 lots", "note", "Checked"));
    }

    private void closedCapa(String ncr) throws Exception {
        String capa = capa(ncr, owner);
        analyse(capa);
        implement(capa, addAction(capa, "CORRECTIVE"));
        verify(manager, capa, true).andExpect(status().isOk());
        act(manager, "/capas/" + capa + "/close", Map.of("note", "Effective"))
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
    }

    private List<String> titles(Session session) throws Exception {
        return JsonPath.read(as(session, get("/api/v1/notifications?size=100"))
                .andReturn().getResponse().getContentAsString(), "$.data.items[*].title");
    }

    @Test
    void anNcrRunsToClosureThroughAVerifiedCapa() throws Exception {
        String ncr = ncrAwaitingAction("MAJOR");
        act(owner, "/ncrs/" + ncr + "/request-verification", Map.of()).andExpect(status().isUnprocessableContent());

        closedCapa(ncr);

        act(owner, "/ncrs/" + ncr + "/request-verification", Map.of("note", "Fixed"))
                .andExpect(jsonPath("$.data.status").value("PENDING_VERIFICATION"));
        String number = read(as(owner, get("/api/v1/ncrs/" + ncr)), "$.data.ncrNumber");
        assertThat(emailedSubjects(manager)).contains(number + " is ready for closure");
        act(owner, "/ncrs/" + ncr + "/close", Map.of("note", "Mine")).andExpect(status().isUnprocessableContent());
        act(manager, "/ncrs/" + ncr + "/close", Map.of("note", "Verified on the floor"))
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.capas[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.data.history.length()").value(6))
                .andExpect(jsonPath("$.data.history[5].actorName").value("Member"));
    }

    @Test
    void theOrganisationDecidesWhichNcrsNeedACapa() throws Exception {
        updateSettings(owner, Map.of("capaRequiredFrom", "CRITICAL"));
        String major = ncrAwaitingAction("MAJOR");
        as(owner, get("/api/v1/ncrs/" + major)).andExpect(jsonPath("$.data.capaRequired").value(false));
        act(owner, "/ncrs/" + major + "/request-verification", Map.of())
                .andExpect(jsonPath("$.data.status").value("PENDING_VERIFICATION"));
        String critical = ncrAwaitingAction("CRITICAL");
        act(owner, "/ncrs/" + critical + "/request-verification", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("A critical NCR needs a closed CAPA."));

        updateSettings(owner, Map.of("capaRequiredFrom", "MINOR"));
        String minor = ncrAwaitingAction("MINOR");
        as(owner, get("/api/v1/ncrs/" + minor)).andExpect(jsonPath("$.data.capaRequired").value(true));
        act(owner, "/ncrs/" + minor + "/request-verification", Map.of()).andExpect(status().isUnprocessableContent());

        updateSettings(owner, Map.of("capaRequiredFrom", "NEVER"));
        act(owner, "/ncrs/" + critical + "/request-verification", Map.of()).andExpect(status().isOk());
    }

    @Test
    void theOrganisationDecidesWhichFixesMustBeVerified() throws Exception {
        updateSettings(owner, Map.of("effectivenessRequiredFrom", "NEVER"));
        String major = ncrAwaitingAction("MAJOR");
        as(owner, get("/api/v1/ncrs/" + major)).andExpect(jsonPath("$.data.effectivenessRequired").value(false));
        act(owner, "/capas", capaRequest(major, owner, FUTURE))
                .andExpect(jsonPath("$.data.effectivenessRequired").value(false));

        updateSettings(owner, Map.of("effectivenessRequiredFrom", "MINOR"));
        act(owner, "/capas", capaRequest(ncrAwaitingAction("MINOR"), owner, FUTURE))
                .andExpect(jsonPath("$.data.effectivenessRequired").value(true));
    }

    @Test
    void aMinorNcrCanCloseWithoutACapaButNotPastAnOpenOne() throws Exception {
        String ncr = ncrAwaitingAction("MINOR");
        String capa = capa(ncr, owner);
        act(owner, "/ncrs/" + ncr + "/request-verification", Map.of()).andExpect(status().isUnprocessableContent());
        act(manager, "/capas/" + capa + "/cancel", Map.of("note", "Raised in error")).andExpect(status().isOk());

        act(owner, "/ncrs/" + ncr + "/request-verification", Map.of()).andExpect(status().isOk());
        act(manager, "/ncrs/" + ncr + "/send-back", Map.of("note", "Containment evidence missing"))
                .andExpect(jsonPath("$.data.status").value("ACTION_REQUIRED"));
    }

    @Test
    void anNcrCannotSkipSteps() throws Exception {
        String ncr = raise(owner, "MINOR");
        act(owner, "/ncrs/" + ncr + "/containment", Map.of("containmentAction", "X"))
                .andExpect(status().isUnprocessableContent());
        act(owner, "/ncrs/" + ncr + "/investigation", Map.of("rootCause", "X"))
                .andExpect(status().isUnprocessableContent());
        act(manager, "/ncrs/" + ncr + "/close", Map.of("note", "X")).andExpect(status().isUnprocessableContent());
        act(owner, "/ncrs/" + ncr + "/cancel", Map.of("note", "Duplicate"))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        act(owner, "/ncrs/" + ncr + "/assign", Map.of("assigneeId", manager.userId()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void investigatingNeedsADefectCategory() throws Exception {
        String ncr = read(act(owner, "/ncrs", Map.of("title", "T", "description", "D", "severity", "MINOR")), "$.data.id");
        act(owner, "/ncrs/" + ncr + "/assign", Map.of("assigneeId", manager.userId())).andExpect(status().isOk());
        act(owner, "/ncrs/" + ncr + "/containment", Map.of("containmentAction", "X")).andExpect(status().isOk());
        act(owner, "/ncrs/" + ncr + "/investigation", Map.of("rootCause", "X"))
                .andExpect(status().isUnprocessableContent());
        act(owner, "/ncrs/" + ncr + "/investigation", Map.of("rootCause", "X", "defectCategoryId", category(owner)))
                .andExpect(status().isOk());
    }

    @Test
    void aSolePersonCanTakeAnNcrToClosureUnderThePolicy() throws Exception {
        String ncr = ncrAwaitingAction("MAJOR");
        String capa = capa(ncr, owner);
        analyse(capa);
        implement(capa, addAction(capa, "CORRECTIVE"));
        allowSinglePersonReview(owner);
        as(owner, postJson("/api/v1/users/" + membershipId(owner, manager.userId()) + "/deactivate", Map.of()))
                .andExpect(status().is2xxSuccessful());

        verify(owner, capa, true).andExpect(status().isOk());
        act(owner, "/capas/" + capa + "/close", Map.of("note", "Effective, checked alone"))
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
        act(owner, "/ncrs/" + ncr + "/request-verification", Map.of("note", "Fixed"))
                .andExpect(jsonPath("$.data.status").value("PENDING_VERIFICATION"));
        act(owner, "/ncrs/" + ncr + "/close", Map.of("note", "Verified on the floor"))
                .andExpect(jsonPath("$.data.status").value("CLOSED"));

        assertThat(selfReviews(owner.tenantId(), "CAPA_VERIFY")).isEqualTo(1);
        assertThat(selfReviews(owner.tenantId(), "CAPA_CLOSE")).isEqualTo(1);
        assertThat(selfReviews(owner.tenantId(), "NCR_CLOSE")).isEqualTo(1);
    }

    @Test
    void aFixMustBeVerifiedByAnotherPersonBeforeClosing() throws Exception {
        String capa = capa(ncrAwaitingAction("MAJOR"), owner);
        analyse(capa);
        implement(capa, addAction(capa, "CORRECTIVE"));

        act(manager, "/capas/" + capa + "/close", Map.of("note", "Done")).andExpect(status().isUnprocessableContent());
        verify(owner, capa, true).andExpect(status().isUnprocessableContent());
        act(owner, "/capas/" + capa + "/close", Map.of("note", "Done")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void anIneffectiveFixReturnsToPlanningAndNeedsANewAction() throws Exception {
        String capa = capa(ncrAwaitingAction("MAJOR"), owner);
        analyse(capa);
        String first = addAction(capa, "CORRECTIVE");
        implement(capa, first);

        verify(manager, capa, false)
                .andExpect(jsonPath("$.data.status").value("ACTION_PLANNING"))
                .andExpect(jsonPath("$.data.actions[0].completionNote").value("Limit in the control plan"));
        act(owner, "/capas/" + capa + "/start-implementation", Map.of()).andExpect(status().isUnprocessableContent());

        implement(capa, addAction(capa, "CORRECTIVE"));
        act(manager, "/capas/" + capa + "/close", Map.of("note", "Done")).andExpect(status().isUnprocessableContent());
        verify(manager, capa, true).andExpect(jsonPath("$.data.verifications.length()").value(2));
        act(manager, "/capas/" + capa + "/close", Map.of("note", "Done")).andExpect(status().isOk());
    }

    @Test
    void openActionsHoldBackVerification() throws Exception {
        String capa = capa(ncrAwaitingAction("MINOR"), owner);
        analyse(capa);
        act(owner, "/capas/" + capa + "/start-implementation", Map.of()).andExpect(status().isUnprocessableContent());
        addAction(capa, "PREVENTIVE");
        act(owner, "/capas/" + capa + "/start-implementation", Map.of()).andExpect(status().isUnprocessableContent());
        addAction(capa, "CORRECTIVE");
        act(owner, "/capas/" + capa + "/start-implementation", Map.of()).andExpect(status().isOk());
        act(owner, "/capas/" + capa + "/submit-verification", Map.of()).andExpect(status().isUnprocessableContent());
    }

    @Test
    void permissionsSeparateRaisingProgressingAndClosing() throws Exception {
        Session inspector = addMember(owner, "QUALITY_INSPECTOR");
        Session viewer = addMember(owner, "VIEWER");
        Session engineer = addMember(owner, "QUALITY_ENGINEER");
        String ncr = raise(owner, "MINOR");

        act(inspector, "/ncrs", Map.of("title", "T", "description", "D", "severity", "MINOR"))
                .andExpect(status().isCreated());
        act(inspector, "/ncrs/" + ncr + "/assign", Map.of("assigneeId", manager.userId()))
                .andExpect(status().isForbidden());
        act(viewer, "/ncrs", Map.of("title", "T", "description", "D", "severity", "MINOR"))
                .andExpect(status().isForbidden());
        as(inspector, get("/api/v1/capas")).andExpect(status().isForbidden());
        act(engineer, "/ncrs/" + ncr + "/reject", Map.of("note", "X")).andExpect(status().isForbidden());
        act(manager, "/ncrs/" + ncr + "/reject", Map.of("note", "Within tolerance"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
    }

    @Test
    void theRightPeopleHearAboutEachStep() throws Exception {
        Session engineer = addMember(owner, "QUALITY_ENGINEER");
        String ncr = raise(owner, "MAJOR");
        String number = read(as(owner, get("/api/v1/ncrs/" + ncr)), "$.data.ncrNumber");
        assertThat(titles(engineer)).contains(number + " was raised");

        act(owner, "/ncrs/" + ncr + "/assign", Map.of("assigneeId", engineer.userId())).andExpect(status().isOk());
        assertThat(titles(engineer)).contains(number + " was assigned to you");

        String capa = capa(null, engineer);
        String capaNumber = read(as(owner, get("/api/v1/capas/" + capa)), "$.data.capaNumber");
        assertThat(titles(engineer)).contains(capaNumber + " was assigned to you");
        analyse(capa);
        implement(capa, addAction(capa, "CORRECTIVE"));
        assertThat(titles(manager)).contains("New action on " + capaNumber, capaNumber + " needs effectiveness verification");
        assertThat(emailedSubjects(manager))
                .contains("New action on " + capaNumber, capaNumber + " needs effectiveness verification");
    }

    @Test
    void anOverdueCapaIsRemindedOnceAWeekAndAFinishedOneNever() throws Exception {
        String capa = capa(null, manager);
        String finished = capa(null, manager);
        act(owner, "/capas/" + finished + "/cancel", Map.of("note", "Superseded")).andExpect(status().isOk());
        String number = read(as(owner, get("/api/v1/capas/" + capa)), "$.data.capaNumber");
        asAppRole(owner.tenantId(), sql -> sql.update(
                "UPDATE corrective_actions SET target_date = current_date - 3 WHERE id = ?", UUID.fromString(capa)));
        as(owner, get("/api/v1/capas/" + capa)).andExpect(jsonPath("$.data.overdue").value(true));

        assertThat(remind()).isEqualTo(1);
        assertThat(remind()).isZero();
        assertThat(titles(manager)).containsOnlyOnce(number + " is overdue");

        asAppRole(owner.tenantId(), sql -> sql.update(
                "UPDATE corrective_actions SET overdue_notified_at = now() - interval '8 days' WHERE id = ?",
                UUID.fromString(capa)));
        assertThat(remind()).isEqualTo(1);
    }

    private int remind() {
        return TenantContext.callAs(owner.tenantId(), null, capaService::remindOverdue);
    }

    @Test
    void aLateActionIsChasedWeeklyUntilItIsDone() throws Exception {
        String capa = capa(null, manager);
        String number = read(as(owner, get("/api/v1/capas/" + capa)), "$.data.capaNumber");
        analyse(capa);
        String action = addAction(capa, "CORRECTIVE");
        asAppRole(owner.tenantId(), sql -> sql.update(
                "UPDATE capa_actions SET target_date = current_date - 2 WHERE id = ?", UUID.fromString(action)));
        asAppRole(null, sql -> assertThat(sql.queryForList("SELECT tenants_with_capas_due()", UUID.class))
                .contains(owner.tenantId()));

        assertThat(remindActions()).isEqualTo(1);
        assertThat(remindActions()).isZero();
        assertThat(titles(manager)).containsOnlyOnce("An action on " + number + " is overdue");
        assertThat(emailedSubjects(manager)).containsOnlyOnce("An action on " + number + " is overdue");

        asAppRole(owner.tenantId(), sql -> sql.update(
                "UPDATE capa_actions SET overdue_notified_at = now() - interval '8 days' WHERE id = ?",
                UUID.fromString(action)));
        act(owner, "/capas/" + capa + "/start-implementation", Map.of()).andExpect(status().isOk());
        act(owner, "/capas/" + capa + "/actions/" + action + "/complete", Map.of("note", "Done"))
                .andExpect(status().isOk());
        assertThat(remindActions()).isZero();
    }

    private int remindActions() {
        return TenantContext.callAs(owner.tenantId(), null, capaService::remindOverdueActions);
    }

    @Test
    void aCapaIsRemindedOnceBeforeItsDueDate() throws Exception {
        String capa = capa(null, manager);
        asAppRole(owner.tenantId(), sql -> sql.update(
                "UPDATE corrective_actions SET target_date = current_date + 2 WHERE id = ?", UUID.fromString(capa)));
        asAppRole(null, sql -> assertThat(sql.queryForList("SELECT tenants_with_capas_due()", UUID.class))
                .contains(owner.tenantId()));

        assertThat(TenantContext.callAs(owner.tenantId(), null, capaService::remindDueSoon)).isEqualTo(1);
        assertThat(TenantContext.callAs(owner.tenantId(), null, capaService::remindDueSoon)).isZero();
        as(manager, get("/api/v1/notifications"))
                .andExpect(jsonPath("$.data.items[0].type").value("CAPA_DUE_SOON"));
        assertThat(inTransactionAs(owner.tenantId(), null, () -> jdbc.queryForObject(
                "SELECT email_template FROM notifications WHERE type = 'CAPA_DUE_SOON' AND tenant_id = ?",
                String.class, owner.tenantId()))).isEqualTo("CAPA_DUE_REMINDER");
    }

    @Test
    void aClosedCapaTellsItsOwnerWithTheClosureEmail() throws Exception {
        closedCapa(null);
        assertThat(inTransactionAs(owner.tenantId(), null, () -> jdbc.queryForList(
                "SELECT email_template FROM notifications WHERE type = 'CAPA_CLOSED'", String.class)))
                .isNotEmpty().containsOnly("CAPA_CLOSURE");
    }

    @Test
    void theOverdueFunctionRevealsOnlyTenantIds() throws Exception {
        String capa = capa(null, manager);
        asAppRole(owner.tenantId(), sql -> sql.update(
                "UPDATE corrective_actions SET target_date = current_date - 3 WHERE id = ?", UUID.fromString(capa)));

        asAppRole(null, sql -> {
            assertThat(sql.queryForList("SELECT tenants_with_overdue_capas()", UUID.class)).contains(owner.tenantId());
            assertThat(sql.queryForObject("SELECT count(*) FROM corrective_actions", Long.class)).isZero();
        });
    }

    @Test
    void aFinishedNcrLocksItsFiles() throws Exception {
        String ncr = raise(owner, "MINOR");
        MockMultipartFile file = new MockMultipartFile(
                "file", "defect.pdf", "application/pdf", "%PDF-1.4 photo".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/attachments").file(file).param("entityType", "NCR").param("entityId", ncr)
                        .header(HttpHeaders.AUTHORIZATION, owner.bearer()))
                .andExpect(status().isCreated());
        act(owner, "/ncrs/" + ncr + "/cancel", Map.of("note", "Duplicate")).andExpect(status().isOk());
        mvc.perform(multipart("/api/v1/attachments").file(file).param("entityType", "NCR").param("entityId", ncr)
                        .header(HttpHeaders.AUTHORIZATION, owner.bearer()))
                .andExpect(status().isUnprocessableContent());
    }

    private ResultActions uploadEvidence(Session session, String action) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "control-plan.pdf", "application/pdf", "%PDF-1.4 plan".getBytes(StandardCharsets.UTF_8));
        return mvc.perform(multipart("/api/v1/attachments").file(file)
                .param("entityType", "CAPA_ACTION").param("entityId", action)
                .header(HttpHeaders.AUTHORIZATION, session.bearer()));
    }

    @Test
    void anOrganisationCanRequireEvidenceBeforeAnActionIsCompleted() throws Exception {
        String capa = capa(null, owner);
        analyse(capa);
        String action = addAction(capa, "CORRECTIVE");
        act(owner, "/capas/" + capa + "/start-implementation", Map.of()).andExpect(status().isOk());
        updateSettings(owner, Map.of("actionEvidenceRequired", true));
        as(owner, get("/api/v1/organizations/current/settings"))
                .andExpect(jsonPath("$.data.actionEvidenceRequired").value(true));

        act(owner, "/capas/" + capa + "/actions/" + action + "/complete", Map.of("note", "Done"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("Attach evidence to this action before completing it."));
        // A file on the CAPA as a whole does not count for the action.
        MockMultipartFile file = new MockMultipartFile(
                "file", "summary.pdf", "application/pdf", "%PDF-1.4 sum".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/attachments").file(file).param("entityType", "CAPA").param("entityId", capa)
                        .header(HttpHeaders.AUTHORIZATION, owner.bearer()))
                .andExpect(status().isCreated());
        act(owner, "/capas/" + capa + "/actions/" + action + "/complete", Map.of("note", "Done"))
                .andExpect(status().isUnprocessableContent());

        uploadEvidence(owner, action).andExpect(status().isCreated());
        act(owner, "/capas/" + capa + "/actions/" + action + "/complete", Map.of("note", "Done"))
                .andExpect(status().isOk());

        // A client that leaves the rule out keeps it as it is.
        Map<String, Object> older = new java.util.HashMap<>(Map.of("singlePersonReview", false,
                "capaRequiredFrom", "MAJOR", "effectivenessRequiredFrom", "MAJOR"));
        older.put("version", JsonPath.read(as(owner, get("/api/v1/organizations/current/settings")).andReturn()
                .getResponse().getContentAsString(),
                "$.data.version"));
        as(owner, put("/api/v1/organizations/current/settings")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(older)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actionEvidenceRequired").value(true));
    }

    private String addActionFor(String capa, Session actionOwner, String target) throws Exception {
        String body = act(owner, "/capas/" + capa + "/actions", Map.of(
                        "actionType", "CORRECTIVE", "description", "Change the fixture", "ownerId", actionOwner.userId(),
                        "targetDate", target))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.actions[-1].id");
    }

    @Test
    void anActionOwnerWhoCannotSeeCapasCanStillReportTheirOwnAction() throws Exception {
        Session production = addMember(owner, "PRODUCTION_MANAGER");
        Session bystander = addMember(owner, "PRODUCTION_MANAGER");
        String capa = capa(null, manager);
        analyse(capa);
        String theirs = addActionFor(capa, production, FUTURE);
        String others = addAction(capa, "CORRECTIVE");

        as(production, get("/api/v1/capas/" + capa)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actions.length()").value(2));
        as(bystander, get("/api/v1/capas/" + capa)).andExpect(status().isNotFound());
        as(production, get("/api/v1/capas")).andExpect(status().isForbidden());

        // Not until the plan is being carried out.
        act(production, "/capas/" + capa + "/actions/" + theirs + "/complete", Map.of("note", "Done"))
                .andExpect(status().isUnprocessableContent());
        act(owner, "/capas/" + capa + "/start-implementation", Map.of()).andExpect(status().isOk());

        uploadEvidence(production, theirs).andExpect(status().isCreated());
        as(production, get("/api/v1/attachments").param("entityType", "CAPA_ACTION").param("entityId", theirs))
                .andExpect(jsonPath("$.data.length()").value(1));
        uploadEvidence(production, others).andExpect(status().isForbidden());
        uploadEvidence(bystander, theirs).andExpect(status().isForbidden());

        act(production, "/capas/" + capa + "/actions/" + others + "/complete", Map.of("note", "Done"))
                .andExpect(status().isForbidden());
        act(bystander, "/capas/" + capa + "/actions/" + theirs + "/complete", Map.of("note", "Done"))
                .andExpect(status().isNotFound());
        act(production, "/capas/" + capa + "/actions/" + theirs + "/complete", Map.of("note", "Fixture changed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actions[0].completionNote").value("Fixture changed"));
        // The rest of the CAPA stays with those who manage it.
        act(production, "/capas/" + capa + "/submit-verification", Map.of()).andExpect(status().isForbidden());
        // Done evidence is part of the record now.
        uploadEvidence(production, theirs).andExpect(status().isForbidden());
    }

    private int escalate() {
        return TenantContext.callAs(owner.tenantId(), null, capaService::escalateLate);
    }

    @Test
    void aCapaOrActionLongLateIsEscalatedOnceToWhoeverApprovesCapas() throws Exception {
        Session engineer = addMember(owner, "QUALITY_ENGINEER");
        Session plant = addMember(owner, "PLANT_MANAGER");
        String capa = capa(null, engineer);
        String number = read(as(owner, get("/api/v1/capas/" + capa)), "$.data.capaNumber");
        analyse(capa);
        String action = addActionFor(capa, engineer, FUTURE);
        asAppRole(owner.tenantId(), sql -> {
            sql.update("UPDATE corrective_actions SET target_date = current_date - 3 WHERE id = ?", UUID.fromString(capa));
            sql.update("UPDATE capa_actions SET target_date = current_date - 3 WHERE id = ?", UUID.fromString(action));
        });
        // Late, but not by the default seven days.
        assertThat(escalate()).isZero();

        asAppRole(owner.tenantId(), sql -> {
            sql.update("UPDATE corrective_actions SET target_date = current_date - 8 WHERE id = ?", UUID.fromString(capa));
            sql.update("UPDATE capa_actions SET target_date = current_date - 10 WHERE id = ?", UUID.fromString(action));
        });
        updateSettings(owner, Map.of("capaEscalationDays", 0));
        assertThat(escalate()).isZero();
        updateSettings(owner, Map.of("capaEscalationDays", 7));
        as(owner, get("/api/v1/organizations/current/settings"))
                .andExpect(jsonPath("$.data.capaEscalationDays").value(7));

        assertThat(escalate()).isEqualTo(2);
        assertThat(escalate()).isZero();
        assertThat(titles(plant)).containsOnlyOnce(number + " is 8 days late").containsOnlyOnce(number + " is 10 days late");
        assertThat(titles(manager)).contains(number + " is 8 days late");
        assertThat(titles(engineer)).doesNotContain(number + " is 8 days late");
        assertThat(emailedSubjects(plant)).contains(number + " is 10 days late");
        assertThat(inTransactionAs(owner.tenantId(), null, () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action IN ('CAPA_ESCALATED', 'CAPA_ACTION_ESCALATED')",
                Long.class))).isEqualTo(2);

        updateSettings(owner, Map.of("capaEscalationDays", 91), status().isBadRequest());
    }

    @Test
    void eachActionKeepsItsOwnEvidenceUntilTheCapaIsFinished() throws Exception {
        String capa = capa(null, owner);
        analyse(capa);
        String first = addAction(capa, "CORRECTIVE");
        String second = addAction(capa, "PREVENTIVE");

        uploadEvidence(owner, first).andExpect(status().isCreated());
        as(owner, get("/api/v1/attachments").param("entityType", "CAPA_ACTION").param("entityId", first))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fileName").value("control-plan.pdf"));
        as(owner, get("/api/v1/attachments").param("entityType", "CAPA_ACTION").param("entityId", second))
                .andExpect(jsonPath("$.data.length()").value(0));

        Session viewer = addMember(owner, "VIEWER");
        uploadEvidence(viewer, second).andExpect(status().isForbidden());
        Session stranger = register();
        uploadEvidence(stranger, second).andExpect(status().isNotFound());
        uploadEvidence(owner, UUID.randomUUID().toString()).andExpect(status().isNotFound());

        act(owner, "/capas/" + capa + "/cancel", Map.of("note", "Superseded")).andExpect(status().isOk());
        uploadEvidence(owner, second)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("This record is closed, so its files cannot change."));
    }

    @Test
    void theDatabaseProtectsClosedRecordsAndHistory() throws Exception {
        String ncr = ncrAwaitingAction("MINOR");
        act(owner, "/ncrs/" + ncr + "/request-verification", Map.of()).andExpect(status().isOk());
        act(manager, "/ncrs/" + ncr + "/close", Map.of("note", "Done")).andExpect(status().isOk());
        UUID id = UUID.fromString(ncr);

        for (String sql : List.of(
                "UPDATE non_conformances SET root_cause = 'Rewritten' WHERE id = ?",
                "DELETE FROM non_conformances WHERE id = ?",
                "UPDATE ncr_status_history SET note = 'Rewritten' WHERE ncr_id = ?",
                "DELETE FROM ncr_status_history WHERE ncr_id = ?")) {
            assertThatThrownBy(() -> asAppRole(owner.tenantId(), statement -> statement.update(sql, id)))
                    .as(sql).isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void aClosedNcrCannotGainACapa() throws Exception {
        String ncr = raise(owner, "MINOR");
        act(owner, "/ncrs/" + ncr + "/cancel", Map.of("note", "Duplicate")).andExpect(status().isOk());
        act(owner, "/capas", capaRequest(ncr, owner, FUTURE)).andExpect(status().isUnprocessableContent());
    }

    @Test
    void aCapaOwnerMustBelongToTheOrganisationAndATargetMustBeAhead() throws Exception {
        Session outsider = register();
        act(owner, "/capas", capaRequest(null, outsider, FUTURE)).andExpect(status().isUnprocessableContent());
        act(owner, "/capas", capaRequest(null, owner, LocalDate.now().minusDays(1).toString()))
                .andExpect(status().isBadRequest());
    }
}
