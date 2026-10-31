package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

class InspectionIT extends MasterDataFixture {

    private static final String TODAY = LocalDate.now().toString();

    private Map<String, Object> parameter(String code, String criticality, String min, String max) {
        Map<String, Object> parameter = new HashMap<>(Map.of(
                "code", code, "name", code, "dataType", "NUMERIC", "criticality", criticality,
                "mandatory", true, "perSample", true, "evidenceRequired", false));
        parameter.put("minValue", min);
        parameter.put("maxValue", max);
        return parameter;
    }

    private Map<String, Object> draft(int samples, int acceptance, List<Map<String, Object>> parameters) {
        return Map.of("samplingType", "FIXED", "sampleSize", samples, "acceptanceNumber", acceptance,
                "parameters", parameters);
    }

    private String createTemplate(Session session) throws Exception {
        return idOfTemplate(body(as(session, postJson("/api/v1/inspection-templates",
                        Map.of("code", code(), "name", "Bar IQC", "inspectionType", "IQC")))
                .andExpect(status().isCreated())));
    }

    private String idOfTemplate(String body) {
        return JsonPath.read(body, "$.data.template.id");
    }

    private String draftVersion(Session session, String template) throws Exception {
        List<String> ids = JsonPath.read(body(as(session, get("/api/v1/inspection-templates/" + template))),
                "$.data.versions[?(@.status == 'DRAFT')].id");
        return ids.getFirst();
    }

    private ResultActions saveDraft(Session session, String template, Map<String, Object> draft) throws Exception {
        return as(session, put("/api/v1/inspection-templates/" + template + "/versions/"
                        + draftVersion(session, template))
                .contentType("application/json").content(json.writeValueAsString(draft)));
    }

    private ResultActions publish(Session session, String template, String from) throws Exception {
        return as(session, postJson("/api/v1/inspection-templates/" + template + "/publish",
                Map.of("effectiveFrom", from)));
    }

    private String publishedTemplate(Session session, Map<String, Object> draft) throws Exception {
        String template = createTemplate(session);
        saveDraft(session, template, draft).andExpect(status().isOk());
        publish(session, template, TODAY).andExpect(status().isOk());
        return template;
    }

    /** One critical diameter between 19.9 and 20.1, on two samples. */
    private String shaftTemplate(Session session) throws Exception {
        return publishedTemplate(session, draft(2, 0, List.of(parameter("DIA", "CRITICAL", "19.9", "20.1"))));
    }

    private String receivedItem(Session session) throws Exception {
        IssuedOrder order = issuedOrder(session, "100");
        return JsonPath.read(body(receive(session, order, "100").andExpect(status().isCreated())),
                "$.data.items[0].id");
    }

    private String startInspection(Session session, String template) throws Exception {
        return idOf(body(as(session, postJson("/api/v1/inspections",
                        Map.of("receiptItemId", receivedItem(session), "templateId", template)))
                .andExpect(status().isCreated())));
    }

    private ResultActions record(Session session, String inspection, String code, int sample, String value)
            throws Exception {
        return as(session, postJson("/api/v1/inspections/" + inspection + "/execute", Map.of("results", List.of(
                Map.of("parameterCode", code, "sampleNumber", sample, "valueNumeric", value)))));
    }

    private ResultActions submit(Session session, String inspection) throws Exception {
        return as(session, postJson("/api/v1/inspections/" + inspection + "/submit", Map.of()));
    }

    private ResultActions dispose(Session session, String inspection, String disposition) throws Exception {
        return as(session, postJson("/api/v1/inspections/" + inspection + "/disposition",
                Map.of("disposition", disposition)));
    }

    /** A completed entry of the shaft template, submitted by {@code inspector}. */
    private String submittedInspection(Session inspector, String template, String first, String second)
            throws Exception {
        String id = startInspection(inspector, template);
        record(inspector, id, "DIA", 1, first).andExpect(status().isOk());
        record(inspector, id, "DIA", 2, second).andExpect(status().isOk());
        submit(inspector, id).andExpect(status().isOk());
        return id;
    }

    @Test
    void passingMaterialFlowsFromReceiptToAcceptance() throws Exception {
        Session owner = register();
        Session manager = addMember(owner, "QUALITY_MANAGER");
        String item = receivedItem(owner);
        as(owner, get("/api/v1/material-receipts/awaiting-inspection"))
                .andExpect(jsonPath("$.data.items[0].itemId").value(item));

        String inspection = idOf(body(as(owner, postJson("/api/v1/inspections",
                        Map.of("receiptItemId", item, "templateId", shaftTemplate(owner))))
                .andExpect(jsonPath("$.data.sampleSize").value(2))
                .andExpect(jsonPath("$.data.specification.versionNumber").value(1))));
        as(owner, get("/api/v1/material-receipts/awaiting-inspection"))
                .andExpect(jsonPath("$.data.totalItems").value(0));

        record(owner, inspection, "DIA", 1, "20.0").andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        record(owner, inspection, "DIA", 2, "20.05").andExpect(jsonPath("$.data.results[1].evaluation").value("PASS"));
        submit(owner, inspection).andExpect(jsonPath("$.data.evaluatedResult").value("PASS"));
        as(manager, postJson("/api/v1/inspections/" + inspection + "/approve", Map.of()))
                .andExpect(jsonPath("$.data.finalResult").value("PASS"));
        dispose(manager, inspection, "ACCEPT")
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.decisions.length()").value(3));

        as(owner, get("/api/v1/audit-logs"))
                .andExpect(jsonPath("$.data.items[*].action", hasItem("MATERIAL_DISPOSITION_RECORDED")));
    }

    @Test
    void failedMaterialCannotBeSilentlyReleased() throws Exception {
        Session owner = register();
        Session manager = addMember(owner, "QUALITY_MANAGER");
        String id = submittedInspection(owner, publishedTemplate(owner,
                draft(2, 0, List.of(parameter("DIA", "MAJOR", "1", "2")))), "1.5", "9");
        as(manager, postJson("/api/v1/inspections/" + id + "/approve", Map.of("result", "PASS")))
                .andExpect(jsonPath("$.data.finalResult").value("FAIL"));

        dispose(manager, id, "ACCEPT").andExpect(status().isUnprocessableContent());
        dispose(manager, id, "CONDITIONAL_ACCEPTANCE").andExpect(status().isUnprocessableContent());
        as(manager, postJson("/api/v1/inspections/" + id + "/disposition", Map.of(
                        "disposition", "CONDITIONAL_ACCEPTANCE", "note", "Customer concession C-14")))
                .andExpect(jsonPath("$.data.disposition").value("CONDITIONAL_ACCEPTANCE"));
    }

    @Test
    void aFailedCriticalMeasurementHoldsTheMaterialAtOnce() throws Exception {
        Session owner = register();
        Session manager = addMember(owner, "QUALITY_MANAGER");
        String id = startInspection(owner, shaftTemplate(owner));
        record(owner, id, "DIA", 1, "20").andExpect(status().isOk());
        record(owner, id, "DIA", 2, "25").andExpect(status().isOk());

        submit(owner, id)
                .andExpect(jsonPath("$.data.evaluatedResult").value("FAIL"))
                .andExpect(jsonPath("$.data.disposition").value("HOLD"));

        as(manager, postJson("/api/v1/inspections/" + id + "/approve", Map.of())).andExpect(status().isOk());
        dispose(manager, id, "HOLD").andExpect(jsonPath("$.data.status").value("APPROVED"));
        dispose(manager, id, "REJECT").andExpect(jsonPath("$.data.status").value("COMPLETED"));
        dispose(manager, id, "REWORK").andExpect(status().isUnprocessableContent());
    }

    @Test
    void anInspectorCannotReviewTheirOwnWork() throws Exception {
        Session owner = register();
        String id = submittedInspection(owner, shaftTemplate(owner), "20", "20");

        as(owner, postJson("/api/v1/inspections/" + id + "/approve", Map.of()))
                .andExpect(status().isUnprocessableContent());
        as(owner, postJson("/api/v1/inspections/" + id + "/send-back", Map.of("note", "Mine")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void permissionsSeparateExecutionApprovalAndDisposition() throws Exception {
        Session owner = register();
        Session inspector = addMember(owner, "QUALITY_INSPECTOR");
        Session viewer = addMember(owner, "VIEWER");
        String template = shaftTemplate(owner);
        String id = startInspection(owner, template);
        record(inspector, id, "DIA", 1, "20").andExpect(status().isOk());
        record(inspector, id, "DIA", 2, "20").andExpect(status().isOk());
        submit(inspector, id).andExpect(status().isOk());

        as(viewer, get("/api/v1/inspections/" + id)).andExpect(status().isOk());
        record(viewer, id, "DIA", 1, "20").andExpect(status().isForbidden());
        as(inspector, postJson("/api/v1/inspections/" + id + "/approve", Map.of())).andExpect(status().isForbidden());
        dispose(inspector, id, "ACCEPT").andExpect(status().isForbidden());
        publish(inspector, template, TODAY).andExpect(status().isForbidden());
    }

    @Test
    void anUnclearResultNeedsTheReviewersDecision() throws Exception {
        Session owner = register();
        Session manager = addMember(owner, "QUALITY_MANAGER");
        Map<String, Object> open = parameter("NOTE", "MINOR", null, null);
        String id = startInspection(owner, publishedTemplate(owner, draft(1, 0, List.of(open))));
        record(owner, id, "NOTE", 1, "5").andExpect(status().isOk());
        submit(owner, id).andExpect(jsonPath("$.data.evaluatedResult").value("PENDING_REVIEW"));

        as(manager, postJson("/api/v1/inspections/" + id + "/approve", Map.of()))
                .andExpect(status().isUnprocessableContent());
        as(manager, postJson("/api/v1/inspections/" + id + "/approve", Map.of("result", "PASS")))
                .andExpect(jsonPath("$.data.finalResult").value("PASS"));
    }

    @Test
    void anIncompleteInspectionCannotBeSubmitted() throws Exception {
        Session owner = register();
        String id = startInspection(owner, shaftTemplate(owner));
        record(owner, id, "DIA", 1, "20").andExpect(status().isOk());

        submit(owner, id)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("DIA is incomplete."));
        record(owner, id, "DIA", 3, "20").andExpect(status().isUnprocessableContent());
        record(owner, id, "OTHER", 1, "20").andExpect(status().isUnprocessableContent());
    }

    @Test
    void requiredEvidenceMustBeUploadedAndThenLocks() throws Exception {
        Session owner = register();
        Map<String, Object> photographed = parameter("DIA", "MINOR", "1", "2");
        photographed.put("evidenceRequired", true);
        String id = startInspection(owner, publishedTemplate(owner, draft(1, 0, List.of(photographed))));
        record(owner, id, "DIA", 1, "1.5").andExpect(status().isOk());
        submit(owner, id).andExpect(jsonPath("$.error.message").value("DIA needs evidence."));

        upload(owner, id).andExpect(status().isCreated());
        submit(owner, id).andExpect(status().isOk());

        upload(owner, id).andExpect(status().isUnprocessableContent());
    }

    private ResultActions upload(Session session, String inspection) throws Exception {
        return mvc.perform(multipart("/api/v1/attachments")
                .file(new MockMultipartFile("file", "photo.pdf", "application/pdf",
                        "%PDF-1.7 x".getBytes(StandardCharsets.UTF_8)))
                .param("entityType", "INSPECTION")
                .param("entityId", inspection)
                .param("category", "DIA")
                .header(HttpHeaders.AUTHORIZATION, session.bearer()));
    }

    @Test
    void sendingBackReopensEntryAndClearsTheHold() throws Exception {
        Session owner = register();
        Session manager = addMember(owner, "QUALITY_MANAGER");
        String id = submittedInspection(owner, shaftTemplate(owner), "20", "25");

        as(manager, postJson("/api/v1/inspections/" + id + "/send-back", Map.of("note", "Remeasure sample 2")))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.disposition").isEmpty());

        record(owner, id, "DIA", 2, "20").andExpect(status().isOk());
        submit(owner, id).andExpect(jsonPath("$.data.evaluatedResult").value("PASS"));
        as(owner, get("/api/v1/audit-logs"))
                .andExpect(jsonPath("$.data.items[*].action", hasItem("INSPECTION_RESULT_CHANGED")));
    }

    @Test
    void publishedVersionsAreFinalAndRevisedByNewVersions() throws Exception {
        Session owner = register();
        String template = shaftTemplate(owner);
        String published = JsonPath.read(body(as(owner, get("/api/v1/inspection-templates/" + template))),
                "$.data.versions[0].id");

        as(owner, put("/api/v1/inspection-templates/" + template + "/versions/" + published)
                        .contentType("application/json")
                        .content(json.writeValueAsString(draft(1, 0, List.of()))))
                .andExpect(status().isUnprocessableContent());

        as(owner, postJson("/api/v1/inspection-templates/" + template + "/versions", Map.of()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.versions[0].versionNumber").value(2))
                .andExpect(jsonPath("$.data.versions[0].parameters[0].code").value("DIA"));
        as(owner, postJson("/api/v1/inspection-templates/" + template + "/versions", Map.of()))
                .andExpect(status().isConflict());

        publish(owner, template, TODAY)
                .andExpect(jsonPath("$.data.template.publishedVersion").value(2))
                .andExpect(jsonPath("$.data.versions[1].status").value("SUPERSEDED"));
    }

    @Test
    void aFutureVersionWaitsForItsDate() throws Exception {
        Session owner = register();
        String template = shaftTemplate(owner);
        as(owner, postJson("/api/v1/inspection-templates/" + template + "/versions", Map.of()))
                .andExpect(status().isCreated());
        publish(owner, template, LocalDate.now().plusDays(30).toString()).andExpect(status().isOk());

        as(owner, postJson("/api/v1/inspections", Map.of("receiptItemId", receivedItem(owner), "templateId", template)))
                .andExpect(jsonPath("$.data.specification.versionNumber").value(1));
    }

    @Test
    void anInspectionKeepsItsSpecificationWhenTheTemplateChanges() throws Exception {
        Session owner = register();
        String template = shaftTemplate(owner);
        String id = startInspection(owner, template);

        as(owner, postJson("/api/v1/inspection-templates/" + template + "/versions", Map.of()))
                .andExpect(status().isCreated());
        saveDraft(owner, template, draft(5, 0, List.of(parameter("DIA", "CRITICAL", "1", "2"))))
                .andExpect(status().isOk());
        publish(owner, template, TODAY).andExpect(status().isOk());

        as(owner, get("/api/v1/inspections/" + id))
                .andExpect(jsonPath("$.data.sampleSize").value(2))
                .andExpect(jsonPath("$.data.specification.parameters[0].minValue").value("19.900000"));
    }

    @Test
    void theDatabaseProtectsHistoryWithoutTheApplication() throws Exception {
        Session owner = register();
        Session manager = addMember(owner, "QUALITY_MANAGER");
        String template = shaftTemplate(owner);
        String id = submittedInspection(owner, template, "20", "20");
        as(manager, postJson("/api/v1/inspections/" + id + "/approve", Map.of())).andExpect(status().isOk());

        List<String> attacks = List.of(
                "UPDATE inspection_parameters SET max_value = 99",
                "DELETE FROM inspection_parameters",
                "UPDATE inspection_template_versions SET acceptance_number = 9 WHERE status = 'PUBLISHED'",
                "UPDATE inspections SET spec_snapshot = '{}'",
                "UPDATE inspection_results SET value_numeric = 1",
                "DELETE FROM inspection_results",
                "UPDATE inspection_approvals SET note = 'x'",
                "DELETE FROM inspection_approvals",
                "UPDATE inspections SET disposition = 'ACCEPT', final_result = 'FAIL'");
        for (String attack : attacks) {
            assertThatThrownBy(() -> inTransactionAs(owner.tenantId(), owner.userId(), () -> jdbc.update(attack)))
                    .as(attack).isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void aBatchCanOnlyBeInspectedOnceAtATime() throws Exception {
        Session owner = register();
        String template = shaftTemplate(owner);
        String item = receivedItem(owner);
        Map<String, String> request = Map.of("receiptItemId", item, "templateId", template);
        List<Callable<Integer>> attempts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            attempts.add(() -> as(owner, postJson("/api/v1/inspections", request))
                    .andReturn().getResponse().getStatus());
        }

        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            for (Future<Integer> result : pool.invokeAll(attempts)) {
                statuses.add(result.get());
            }
        }

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
    }

    @Test
    void cancellingReturnsTheBatchToTheQueueAndInspectionsBlockVoiding() throws Exception {
        Session owner = register();
        String template = shaftTemplate(owner);
        IssuedOrder order = issuedOrder(owner, "100");
        String receipt = body(receive(owner, order, "100"));
        String receiptId = idOf(receipt);
        String item = JsonPath.read(receipt, "$.data.items[0].id");
        Map<String, String> request = Map.of("receiptItemId", item, "templateId", template);
        String id = idOf(body(as(owner, postJson("/api/v1/inspections", request))));

        as(owner, postJson("/api/v1/material-receipts/" + receiptId + "/void", Map.of("reason", "Mistake")))
                .andExpect(status().isUnprocessableContent());

        as(owner, postJson("/api/v1/inspections/" + id + "/cancel", Map.of()))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        as(owner, postJson("/api/v1/material-receipts/" + receiptId + "/void", Map.of("reason", "Mistake")))
                .andExpect(status().isOk());
        as(owner, postJson("/api/v1/inspections", request)).andExpect(status().isUnprocessableContent());
    }

    @Test
    void aTemplateForAnotherMaterialIsRefused() throws Exception {
        Session owner = register();
        String template = idOfTemplate(body(as(owner, postJson("/api/v1/inspection-templates", Map.of(
                "code", code(), "name", "Only this", "inspectionType", "IQC", "materialId", material(owner))))));
        saveDraft(owner, template, draft(1, 0, List.of(parameter("A", "MINOR", "1", "2")))).andExpect(status().isOk());
        publish(owner, template, TODAY).andExpect(status().isOk());

        as(owner, postJson("/api/v1/inspections", Map.of("receiptItemId", receivedItem(owner), "templateId", template)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void percentagePlansRoundUpAndOversizedPlansAreRefused() throws Exception {
        Session owner = register();
        Map<String, Object> percent = Map.of("samplingType", "PERCENTAGE", "samplePercent", "2.5",
                "acceptanceNumber", 0, "parameters", List.of(parameter("A", "MINOR", "1", "2")));
        Map<String, Object> full = Map.of("samplingType", "FULL", "acceptanceNumber", 0,
                "parameters", List.of(parameter("A", "MINOR", "1", "2")));

        as(owner, postJson("/api/v1/inspections", Map.of(
                        "receiptItemId", receivedItem(owner), "templateId", publishedTemplate(owner, percent))))
                .andExpect(jsonPath("$.data.sampleSize").value(3));

        IssuedOrder big = issuedOrder(owner, "1000");
        String item = JsonPath.read(body(receive(owner, big, "1000")), "$.data.items[0].id");
        as(owner, postJson("/api/v1/inspections", Map.of(
                        "receiptItemId", item, "templateId", publishedTemplate(owner, full))))
                .andExpect(status().isUnprocessableContent());
    }

    private void assertTitles(Session session, String... titles) throws Exception {
        List<String> found = JsonPath.read(body(as(session, get("/api/v1/notifications"))), "$.data.items[*].title");
        assertThat(found).containsExactlyInAnyOrder(titles);
    }

    private String numberOf(Session session, String inspection) throws Exception {
        return JsonPath.read(body(as(session, get("/api/v1/inspections/" + inspection))), "$.data.inspectionNumber");
    }

    @Test
    void eachStepOfAnInspectionTellsTheRightPeople() throws Exception {
        Session owner = register();
        Session manager = addMember(owner, "QUALITY_MANAGER");
        Session viewer = addMember(owner, "VIEWER");
        Session inspector = addMember(owner, "QUALITY_INSPECTOR");
        String template = publishedTemplate(owner, draft(2, 0, List.of(parameter("DIA", "MAJOR", "1", "2"))));

        String id = startInspection(owner, template);
        String number = numberOf(owner, id);
        record(inspector, id, "DIA", 1, "1.5").andExpect(status().isOk());
        record(inspector, id, "DIA", 2, "1.6").andExpect(status().isOk());
        submit(inspector, id).andExpect(status().isOk());
        assertTitles(manager, number + " is waiting for approval");
        assertTitles(inspector);
        assertTitles(viewer);

        as(manager, postJson("/api/v1/inspections/" + id + "/send-back", Map.of("note", "Remeasure sample 2")))
                .andExpect(status().isOk());
        assertTitles(inspector, number + " was sent back");

        submit(inspector, id).andExpect(status().isOk());
        as(manager, postJson("/api/v1/inspections/" + id + "/approve", Map.of())).andExpect(status().isOk());
        assertTitles(owner, number + " is waiting for approval", number + " is waiting for approval",
                number + " needs a disposition");
        dispose(owner, id, "ACCEPT").andExpect(status().isOk());
        assertTitles(inspector, number + " was sent back", "Disposition recorded for " + number);
        assertTitles(viewer);
    }

    @Test
    void aCriticalFailureRaisesAnAlertInsteadOfARoutineReview() throws Exception {
        Session owner = register();
        Session manager = addMember(owner, "QUALITY_MANAGER");
        String id = submittedInspection(owner, shaftTemplate(owner), "20.0", "25");

        assertTitles(manager, "Critical failure on " + numberOf(owner, id));
        as(manager, get("/api/v1/notifications")).andExpect(jsonPath("$.data.items[0].link").value("/inspections/" + id));
    }
}
