package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.auditmanagement.service.QualityAuditService;
import com.qualityos.common.tenant.TenantContext;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

class AuditManagementIT extends TraceabilityFixture {

    private static final String AUDITS = "/api/v1/audits";
    private static final String CHECKLISTS = "/api/v1/audit-checklists";

    @Autowired
    private QualityAuditService audits;

    private String checklist() throws Exception {
        return idOf(body(as(owner, postJson(CHECKLISTS, Map.of("code", code(), "name", "Shop floor", "items", List.of(
                        Map.of("question", "Are gauges labelled?", "clauseReference", "7.1.5"),
                        Map.of("question", "Are records kept?")))))
                .andExpect(status().isCreated())));
    }

    private Map<String, Object> plan(String type) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("auditType", type);
        body.put("title", "Shop floor audit");
        body.put("scope", "Machining");
        body.put("facilityId", facility(owner));
        body.put("checklistId", checklist());
        body.put("plannedOn", TODAY);
        body.put("leadAuditorId", manager.userId().toString());
        return body;
    }

    private String planned() throws Exception {
        return idOf(body(as(owner, postJson(AUDITS, plan("INTERNAL"))).andExpect(status().isCreated())));
    }

    private String started() throws Exception {
        String audit = planned();
        as(owner, postJson(AUDITS + "/" + audit + "/start", Map.of())).andExpect(status().isOk());
        return audit;
    }

    private String itemId(String audit, int index) throws Exception {
        return JsonPath.read(body(as(owner, get(AUDITS + "/" + audit))), "$.data.items[" + index + "].id");
    }

    private void answer(String audit, int index, String result) throws Exception {
        as(owner, put(AUDITS + "/" + audit + "/items/" + itemId(audit, index)).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("result", result))))
                .andExpect(status().isOk());
    }

    private ResultActions finding(String audit, String item, String grade) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("grade", grade, "statement", "Gauge unlabelled",
                "evidence", "Caliper VC-9 had no label"));
        body.put("auditItemId", item);
        return as(owner, postJson(AUDITS + "/" + audit + "/findings", body));
    }

    private ResultActions complete(String audit) throws Exception {
        return as(owner, postJson(AUDITS + "/" + audit + "/complete", Map.of("summary", "Two questions reviewed")));
    }

    private ResultActions requestClosure(Session session, String audit) throws Exception {
        return as(session, postJson(AUDITS + "/" + audit + "/request-closure", Map.of("note", "All done")));
    }

    private String completedClean() throws Exception {
        String audit = started();
        answer(audit, 0, "CONFORMING");
        answer(audit, 1, "NOT_APPLICABLE");
        complete(audit).andExpect(status().isOk());
        return audit;
    }

    @Test
    void aFindingOfNonconformityRaisesAnNcrAtOnce() throws Exception {
        String audit = started();
        String response = body(finding(audit, itemId(audit, 0), "MAJOR_NONCONFORMITY").andExpect(status().isCreated()));
        String ncr = JsonPath.read(response, "$.data.findings[0].ncr.id");
        as(owner, get("/api/v1/ncrs/" + ncr))
                .andExpect(jsonPath("$.data.source").value("AUDIT"))
                .andExpect(jsonPath("$.data.severity").value("MAJOR"))
                .andExpect(jsonPath("$.data.auditId").value(audit));
    }

    @Test
    void anObservationRaisesNoNcrUntilSomeoneAsks() throws Exception {
        String audit = started();
        String response = body(finding(audit, null, "OBSERVATION").andExpect(status().isCreated()));
        assertThat((Object) JsonPath.read(response, "$.data.findings[0].ncr")).isNull();
        String id = JsonPath.read(response, "$.data.findings[0].id");
        String first = JsonPath.read(body(as(owner, postJson(AUDITS + "/" + audit + "/findings/" + id + "/ncr", Map.of()))
                .andExpect(status().isOk())), "$.data.findings[0].ncr.id");
        String again = JsonPath.read(body(as(owner, postJson(AUDITS + "/" + audit + "/findings/" + id + "/ncr", Map.of()))
                .andExpect(status().isOk())), "$.data.findings[0].ncr.id");
        assertThat(again).isEqualTo(first);
    }

    @Test
    void aSupplierAuditNeedsASupplierAndPassesItToTheNcr() throws Exception {
        Map<String, Object> body = plan("SUPPLIER");
        as(owner, postJson(AUDITS, body)).andExpect(status().isUnprocessableEntity());
        String supplier = draftSupplier(owner);
        body.put("supplierId", supplier);
        String audit = idOf(body(as(owner, postJson(AUDITS, body)).andExpect(status().isCreated())));
        as(owner, postJson(AUDITS + "/" + audit + "/start", Map.of())).andExpect(status().isOk());
        String ncr = JsonPath.read(body(finding(audit, null, "MINOR_NONCONFORMITY")), "$.data.findings[0].ncr.id");
        as(owner, get("/api/v1/ncrs/" + ncr))
                .andExpect(jsonPath("$.data.supplierId").value(supplier))
                .andExpect(jsonPath("$.data.severity").value("MINOR"));
    }

    @Test
    void changingAChecklistLeavesAnExistingAuditAlone() throws Exception {
        Map<String, Object> body = plan("INTERNAL");
        String checklist = (String) body.get("checklistId");
        String audit = idOf(body(as(owner, postJson(AUDITS, body)).andExpect(status().isCreated())));
        as(owner, put(CHECKLISTS + "/" + checklist).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("code", "X", "name", "Changed",
                                "items", List.of(Map.of("question", "Something else"))))))
                .andExpect(status().isOk());
        as(owner, get(CHECKLISTS + "/" + checklist)).andExpect(jsonPath("$.data.items.length()").value(1));
        as(owner, get(AUDITS + "/" + audit))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].question").value("Are gauges labelled?"));
    }

    @Test
    void anAuditCannotCompleteWithUnansweredQuestionsOrUnexplainedNonconformities() throws Exception {
        String audit = started();
        answer(audit, 0, "NONCONFORMING");
        complete(audit).andExpect(status().isUnprocessableEntity());
        answer(audit, 1, "CONFORMING");
        complete(audit).andExpect(status().isUnprocessableEntity());
        finding(audit, itemId(audit, 0), "MINOR_NONCONFORMITY").andExpect(status().isCreated());
        complete(audit).andExpect(status().isOk());
    }

    @Test
    void aFindingAgainstAQuestionCitesItsClauseAndAgreesWithItsAnswer() throws Exception {
        String audit = started();
        String gauges = itemId(audit, 0);
        answer(audit, 0, "CONFORMING");
        finding(audit, gauges, "MAJOR_NONCONFORMITY").andExpect(status().isUnprocessableEntity())
                .andExpect(content().string(containsString("was answered Conforming")));

        answer(audit, 0, "NONCONFORMING");
        finding(audit, gauges, "MINOR_NONCONFORMITY").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.findings[0].clauseReference").value("7.1.5"));
        as(owner, put(AUDITS + "/" + audit + "/items/" + gauges).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("result", "CONFORMING"))))
                .andExpect(status().isUnprocessableEntity());
        // An observation on a conforming question is fine.
        finding(audit, itemId(audit, 1), "OBSERVATION").andExpect(status().isCreated());
    }

    @Test
    void aCompletedAuditTakesNoNewAnswersOrFindings() throws Exception {
        String audit = completedClean();
        finding(audit, null, "OBSERVATION").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void anOpenNcrBlocksClosure() throws Exception {
        String audit = started();
        answer(audit, 0, "NONCONFORMING");
        answer(audit, 1, "CONFORMING");
        String ncr = JsonPath.read(body(finding(audit, itemId(audit, 0), "MINOR_NONCONFORMITY")), "$.data.findings[0].ncr.id");
        complete(audit).andExpect(status().isOk());
        as(owner, get(AUDITS + "/" + audit)).andExpect(jsonPath("$.data.review.blockers.length()").value(1));
        requestClosure(owner, audit).andExpect(status().isUnprocessableEntity());

        as(owner, postJson("/api/v1/ncrs/" + ncr + "/cancel", Map.of("note", "Raised in error")))
                .andExpect(status().isOk());
        requestClosure(owner, audit).andExpect(status().isOk());
    }

    @Test
    void aSoleMemberClosesTheirOwnAuditWithAReason() throws Exception {
        String audit = completedClean();
        requestClosure(owner, audit).andExpect(status().isOk());
        workAlone();
        as(owner, postJson(AUDITS + "/" + audit + "/close", Map.of())).andExpect(status().isUnprocessableEntity());
        as(owner, postJson(AUDITS + "/" + audit + "/close", Map.of("note", "Sole auditor")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
        assertThat(selfReviews(owner.tenantId(), "AUDIT_CLOSE")).isEqualTo(1);
    }

    @Test
    void closingTakesASecondPersonAndThenFreezesTheAudit() throws Exception {
        String audit = completedClean();
        requestClosure(owner, audit).andExpect(status().isOk());
        String number = JsonPath.read(body(as(owner, get(AUDITS + "/" + audit))), "$.data.auditNumber");
        assertThat(emailedSubjects(manager)).contains(number + " is ready for closure");
        as(owner, postJson(AUDITS + "/" + audit + "/close", Map.of())).andExpect(status().isUnprocessableEntity());
        as(manager, postJson(AUDITS + "/" + audit + "/close", Map.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
        requestClosure(owner, audit).andExpect(status().isUnprocessableEntity());
        as(owner, postJson(AUDITS + "/" + audit + "/cancel", Map.of("note", "x"))).andExpect(status().isUnprocessableEntity());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> asDatabaseSuperuser().update(
                        "UPDATE audit_items SET note = 'changed' WHERE audit_id = ?::uuid", audit))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void closureCanBeSentBack() throws Exception {
        String audit = completedClean();
        requestClosure(owner, audit).andExpect(status().isOk());
        as(manager, postJson(AUDITS + "/" + audit + "/send-back", Map.of("note", "Add the shift B records")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void anAuditWithFindingsCannotBeCancelled() throws Exception {
        String audit = started();
        finding(audit, null, "OBSERVATION").andExpect(status().isCreated());
        as(owner, postJson(AUDITS + "/" + audit + "/cancel", Map.of("note", "x"))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void theLeadAuditorHearsOfTheAudit() throws Exception {
        planned();
        as(manager, get("/api/v1/notifications")).andExpect(jsonPath("$.data.items[0].type").value("AUDIT_SCHEDULED"));
    }

    @Test
    void anAuditorWithoutClosePermissionCannotClose() throws Exception {
        String audit = completedClean();
        requestClosure(owner, audit).andExpect(status().isOk());
        Session auditor = addMember(owner, "AUDITOR");
        as(auditor, get(AUDITS + "/" + audit)).andExpect(status().isOk());
        as(auditor, postJson(AUDITS + "/" + audit + "/close", Map.of())).andExpect(status().isForbidden());
    }

    @Test
    void anotherOrganisationCannotReachAnAudit() throws Exception {
        String audit = started();
        Session stranger = register();
        as(stranger, get(AUDITS + "/" + audit)).andExpect(status().isNotFound());
        as(stranger, get(AUDITS + "/" + audit + "/report.pdf")).andExpect(status().isNotFound());
        as(stranger, get(AUDITS)).andExpect(jsonPath("$.data.totalItems").value(0));
        as(stranger, get(CHECKLISTS)).andExpect(jsonPath("$.data.totalItems").value(0));
    }

    @Test
    void aMemberLimitedToAnotherFacilityCannotSeeTheAudit() throws Exception {
        String audit = planned();
        String other = facility(owner);
        Session limited = addMember(owner, "QUALITY_MANAGER");
        limit(limited, other);
        as(limited, get(AUDITS + "/" + audit)).andExpect(status().isNotFound());
        as(limited, get(AUDITS)).andExpect(jsonPath("$.data.totalItems").value(0));
    }

    @Test
    void theReportIsAPdfAndClaimsNoCertification() throws Exception {
        String audit = completedClean();
        as(owner, get(AUDITS + "/" + audit + "/report"))
                .andExpect(jsonPath("$.data.audit.review.conforming").value(1));
        byte[] pdf = as(owner, get(AUDITS + "/" + audit + "/report.pdf"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    private int remind() {
        return TenantContext.callAs(owner.tenantId(), null, audits::remindUpcoming);
    }

    private String auditNumber(String audit) throws Exception {
        return JsonPath.read(body(as(owner, get(AUDITS + "/" + audit))), "$.data.auditNumber");
    }

    @Test
    void theLeadAuditorIsRemindedOnceAWeekBeforeAndAgainAfterTheDateMoves() throws Exception {
        Map<String, Object> body = plan("INTERNAL");
        String inThreeDays = LocalDate.parse(TODAY).plusDays(3).toString();
        body.put("plannedOn", inThreeDays);
        String audit = idOf(body(as(owner, postJson(AUDITS, body)).andExpect(status().isCreated())));
        Map<String, Object> later = new HashMap<>(plan("INTERNAL"));
        later.put("plannedOn", LocalDate.parse(TODAY).plusDays(30).toString());
        as(owner, postJson(AUDITS, later)).andExpect(status().isCreated());
        String number = auditNumber(audit);

        assertThat(remind()).isEqualTo(1);
        assertThat(remind()).isZero();
        String written = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.of("en", "IN"))
                .format(LocalDate.parse(inThreeDays));
        assertThat(emailedSubjects(manager)).containsOnlyOnce(number + " is on " + written);

        String inFiveDays = LocalDate.parse(TODAY).plusDays(5).toString();
        as(owner, put(AUDITS + "/" + audit).contentType("application/json").content(json.writeValueAsString(Map.of(
                        "title", "Shop floor audit", "scope", "Machining", "plannedOn", inFiveDays,
                        "leadAuditorId", manager.userId().toString()))))
                .andExpect(status().isOk());
        assertThat(remind()).isEqualTo(1);
    }

    @Test
    void closingARepeatingAuditPlansTheNextOne() throws Exception {
        Session lead = addMember(owner, "AUDITOR");
        Map<String, Object> body = plan("INTERNAL");
        body.put("repeatEveryMonths", 6);
        body.put("leadAuditorId", lead.userId().toString());
        String audit = idOf(body(as(owner, postJson(AUDITS, body)).andExpect(status().isCreated())));
        as(owner, get(AUDITS + "/" + audit)).andExpect(jsonPath("$.data.repeatEveryMonths").value(6))
                .andExpect(jsonPath("$.data.nextAuditId").doesNotExist());
        as(owner, postJson(AUDITS + "/" + audit + "/start", Map.of())).andExpect(status().isOk());
        answer(audit, 0, "CONFORMING");
        answer(audit, 1, "CONFORMING");
        complete(audit).andExpect(status().isOk());
        requestClosure(owner, audit).andExpect(status().isOk());

        String closed = body(as(manager, postJson(AUDITS + "/" + audit + "/close", Map.of())).andExpect(status().isOk()));
        String next = JsonPath.read(closed, "$.data.nextAuditId");
        String nextNumber = JsonPath.read(closed, "$.data.nextAuditNumber");
        as(owner, get(AUDITS + "/" + next))
                .andExpect(jsonPath("$.data.status").value("PLANNED"))
                .andExpect(jsonPath("$.data.auditNumber").value(nextNumber))
                .andExpect(jsonPath("$.data.plannedOn").value(LocalDate.parse(TODAY).plusMonths(6).toString()))
                .andExpect(jsonPath("$.data.repeatEveryMonths").value(6))
                .andExpect(jsonPath("$.data.leadAuditorId").value(lead.userId().toString()))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].result").doesNotExist());
        assertThat(emailedSubjects(lead)).contains("Audit " + nextNumber + " is scheduled");
    }

    @Test
    void aRepeatIsSkippedWhenItsChecklistIsArchived() throws Exception {
        Map<String, Object> body = plan("INTERNAL");
        body.put("repeatEveryMonths", 12);
        String audit = idOf(body(as(owner, postJson(AUDITS, body)).andExpect(status().isCreated())));
        as(owner, postJson(AUDITS + "/" + audit + "/start", Map.of())).andExpect(status().isOk());
        answer(audit, 0, "CONFORMING");
        answer(audit, 1, "CONFORMING");
        complete(audit).andExpect(status().isOk());
        requestClosure(owner, audit).andExpect(status().isOk());
        as(owner, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                CHECKLISTS + "/" + body.get("checklistId"))).andExpect(status().isNoContent());

        as(manager, postJson(AUDITS + "/" + audit + "/close", Map.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.nextAuditId").doesNotExist());
    }
}
