package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.ResultActions;

class RecallIT extends TraceabilityFixture {

    private String batch;
    private String customer;
    private String dispatch;
    private String line;

    @BeforeEach
    void delivered() throws Exception {
        batch = releasedBatch(releasedLot("100").id(), "100", "100");
        customer = customer();
        String response = body(dispatch(customer, batch, "100", List.of()).andExpect(status().isCreated()));
        dispatch = idOf(response);
        line = JsonPath.read(response, "$.data.lines[0].id");
        ship(dispatch).andExpect(status().isOk());
    }

    private String start() throws Exception {
        return idOf(body(as(owner, postJson("/api/v1/recalls", Map.of(
                        "batchNumbers", List.of(numberOf(batch)), "recallDate", TODAY,
                        "reason", "Cracks found in the shaft", "instructions", "Stop use and return all units")))
                .andExpect(status().isCreated())));
    }

    private ResultActions attempt(String recall, String method, String outcome) throws Exception {
        return as(owner, postJson("/api/v1/recalls/" + recall + "/customers/" + customer + "/contact-attempts", Map.of(
                "method", method, "attemptedAt", Instant.now().minusSeconds(60).toString(), "outcome", outcome)));
    }

    private ResultActions acknowledge(String recall) throws Exception {
        return as(owner, postJson("/api/v1/recalls/" + recall + "/acknowledgements", Map.of(
                "customerId", customer, "acknowledgedOn", TODAY, "acknowledgedBy", "Asha Rao",
                "customerResponse", "Stock quarantined")));
    }

    private ResultActions contactStatus(String recall, String next) throws Exception {
        return as(owner, postJson("/api/v1/recalls/" + recall + "/customers/" + customer + "/status",
                Map.of("status", next)));
    }

    private void returnAll() throws Exception {
        as(owner, postJson("/api/v1/dispatches/" + dispatch + "/reversals", Map.of(
                        "returnReference", "RMA-" + code(), "reversalDate", TODAY, "reason", "Recall",
                        "items", List.of(Map.of("dispatchItemId", line, "quantity", "100")))))
                .andExpect(status().isCreated());
    }

    private ResultActions requestClosure(Session session, String recall, boolean acceptOutstanding) throws Exception {
        return as(session, postJson("/api/v1/recalls/" + recall + "/request-closure",
                Map.of("note", "All accounted for", "acceptOutstanding", acceptOutstanding)));
    }

    private String resolved() throws Exception {
        String recall = start();
        attempt(recall, "PHONE", "REACHED").andExpect(status().isCreated());
        acknowledge(recall).andExpect(status().isCreated());
        contactStatus(recall, "RESOLVED").andExpect(status().isOk());
        return recall;
    }

    @Test
    void aRecallOpensOneContactPerCustomerWhoReceivedTheBatch() throws Exception {
        as(owner, postJson("/api/v1/customers", Map.of("code", code(), "name", "Bajaj Auto")))
                .andExpect(status().isCreated());
        String recall = start();
        as(owner, get("/api/v1/recalls/" + recall + "/customers"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].customerName").value("Tata Motors"))
                .andExpect(jsonPath("$.data[0].outstandingQuantity").value("100.000000"));
    }

    @Test
    void anUnknownBatchIsRefused() throws Exception {
        as(owner, postJson("/api/v1/recalls", Map.of("batchNumbers", List.of("NOPE"), "recallDate", TODAY,
                        "reason", "x", "instructions", "y")))
                .andExpect(status().isNotFound());
    }

    @Test
    void sendingAnEmailDoesNotCountAsReachingSomeone() throws Exception {
        String recall = start();
        attempt(recall, "EMAIL", "NO_REPLY")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("CONTACT_ATTEMPTED"));
        as(owner, get("/api/v1/recalls/" + recall + "/dashboard"))
                .andExpect(jsonPath("$.data.contactAttempts").value(1))
                .andExpect(jsonPath("$.data.contacted").value(0))
                .andExpect(jsonPath("$.data.acknowledged").value(0));
    }

    @Test
    void everyAttemptIsKeptInOrder() throws Exception {
        String recall = start();
        attempt(recall, "EMAIL", "NO_REPLY").andExpect(status().isCreated());
        attempt(recall, "PHONE", "FAILED").andExpect(status().isCreated());
        attempt(recall, "WHATSAPP", "REACHED")
                .andExpect(jsonPath("$.data.status").value("CONTACTED"))
                .andExpect(jsonPath("$.data.attempts").value(3));
        acknowledge(recall)
                .andExpect(jsonPath("$.data.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data.acknowledgedOn").value(TODAY));
        as(owner, get("/api/v1/recalls/" + recall + "/contact-history"))
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[3].kind").value("ACKNOWLEDGEMENT"))
                .andExpect(jsonPath("$.data[?(@.method == 'EMAIL')]").isNotEmpty())
                .andExpect(jsonPath("$.data[?(@.kind == 'ACKNOWLEDGEMENT')].contactPerson").value("Asha Rao"));
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), sql -> sql.update(
                "DELETE FROM recall_contact_attempts WHERE tenant_id = current_tenant_id()")))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), sql -> sql.update(
                "UPDATE recall_acknowledgements SET acknowledged_by = 'Someone else'")))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void aLaterAttemptDoesNotUndoProgress() throws Exception {
        String recall = start();
        attempt(recall, "PHONE", "REACHED").andExpect(status().isCreated());
        attempt(recall, "EMAIL", "NO_REPLY").andExpect(jsonPath("$.data.status").value("CONTACTED"));
    }

    @Test
    void statusesMustBeEarned() throws Exception {
        String recall = start();
        contactStatus(recall, "RESOLVED").andExpect(status().isUnprocessableContent());
        contactStatus(recall, "UNREACHABLE").andExpect(status().isUnprocessableContent());
        contactStatus(recall, "RETURN_IN_PROGRESS").andExpect(status().isUnprocessableContent());
        contactStatus(recall, "ACKNOWLEDGED").andExpect(status().isUnprocessableContent());
        attempt(recall, "PHONE", "NO_REPLY").andExpect(status().isCreated());
        contactStatus(recall, "UNREACHABLE").andExpect(jsonPath("$.data.status").value("UNREACHABLE"));
        attempt(recall, "PHONE", "REACHED").andExpect(jsonPath("$.data.status").value("CONTACTED"));
        contactStatus(recall, "RETURN_IN_PROGRESS").andExpect(jsonPath("$.data.status").value("RETURN_IN_PROGRESS"));
    }

    @Test
    void anAttemptOrAcknowledgementCannotBeInTheFuture() throws Exception {
        String recall = start();
        as(owner, postJson("/api/v1/recalls/" + recall + "/customers/" + customer + "/contact-attempts", Map.of(
                        "method", "PHONE", "attemptedAt", Instant.now().plusSeconds(600).toString(),
                        "outcome", "REACHED")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void theDashboardFiltersAndCountsFollowUps() throws Exception {
        String recall = start();
        as(owner, put("/api/v1/recalls/" + recall + "/customers/" + customer).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("assignedTo", manager.userId(), "followUpOn", TODAY))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignedName").isNotEmpty());
        as(owner, get("/api/v1/recalls/" + recall + "/dashboard"))
                .andExpect(jsonPath("$.data.totalCustomers").value(1))
                .andExpect(jsonPath("$.data.pending").value(1))
                .andExpect(jsonPath("$.data.outstandingFollowUps").value(1))
                .andExpect(jsonPath("$.data.outstandingQuantity").value("100.000000"));
        String base = "/api/v1/recalls/" + recall + "/customers";
        as(owner, get(base + "?status=PENDING")).andExpect(jsonPath("$.data.length()").value(1));
        as(owner, get(base + "?status=RESOLVED")).andExpect(jsonPath("$.data.length()").value(0));
        as(owner, get(base + "?assignedTo=" + manager.userId())).andExpect(jsonPath("$.data.length()").value(1));
        as(owner, get(base + "?assignedTo=" + owner.userId())).andExpect(jsonPath("$.data.length()").value(0));
        as(owner, get(base + "?customerId=" + customer)).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void returnsFlowIntoTheRecallAsTheyArrive() throws Exception {
        String recall = start();
        returnAll();
        as(owner, get("/api/v1/recalls/" + recall + "/customers"))
                .andExpect(jsonPath("$.data[0].returnedQuantity").value("100.000000"));
        as(owner, get("/api/v1/recalls/" + recall))
                .andExpect(jsonPath("$.data.review.customers").value(1));
    }

    @Test
    void identifyingCustomersIsNotEnoughToClose() throws Exception {
        String recall = start();
        requestClosure(owner, recall, true).andExpect(status().isUnprocessableContent());
    }

    @Test
    void outstandingGoodsMustBeAcknowledgedBeforeClosure() throws Exception {
        String recall = resolved();
        requestClosure(owner, recall, false).andExpect(status().isUnprocessableContent());
        requestClosure(owner, recall, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_CLOSURE"));
    }

    @Test
    void closureNeedsASecondPersonAndIsFinal() throws Exception {
        String recall = resolved();
        returnAll();
        requestClosure(owner, recall, false).andExpect(status().isOk());
        attempt(recall, "PHONE", "REACHED").andExpect(status().isUnprocessableContent());

        as(owner, postJson("/api/v1/recalls/" + recall + "/close", Map.of()))
                .andExpect(status().isUnprocessableContent());
        as(manager, postJson("/api/v1/recalls/" + recall + "/close", Map.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closedBy").isNotEmpty());

        as(manager, postJson("/api/v1/recalls/" + recall + "/close", Map.of()))
                .andExpect(status().isUnprocessableContent());
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), sql -> sql.update(
                "UPDATE recall_contact_records SET notes = 'changed' WHERE recall_id = ?::uuid", recall)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), sql -> sql.update(
                "UPDATE recalls SET reason = 'changed' WHERE id = ?::uuid", recall)))
                .isInstanceOf(DataAccessException.class);
        assertThat(asDatabaseSuperuser().queryForList(
                "SELECT action FROM audit_logs WHERE entity_id = ?", String.class, recall))
                .contains("RECALL_CREATED", "RECALL_CLOSURE_REQUESTED", "RECALL_CLOSED");
    }

    @Test
    void aSentBackClosureReopensTheRecall() throws Exception {
        String recall = resolved();
        requestClosure(owner, recall, true).andExpect(status().isOk());
        as(manager, postJson("/api/v1/recalls/" + recall + "/send-back", Map.of("reason", "Check the outstanding goods")))
                .andExpect(jsonPath("$.data.status").value("OPEN"));
        attempt(recall, "PHONE", "REACHED").andExpect(status().isCreated());
    }

    @Test
    void anUnreachableCustomerNeedsAnAuthorisedException() throws Exception {
        String recall = start();
        String exception = "/api/v1/recalls/" + recall + "/customers/" + customer + "/exception";
        attempt(recall, "PHONE", "NO_REPLY").andExpect(status().isCreated());
        as(owner, postJson(exception, Map.of("reason", "Too early"))).andExpect(status().isUnprocessableContent());
        contactStatus(recall, "UNREACHABLE").andExpect(status().isOk());
        requestClosure(owner, recall, true).andExpect(status().isUnprocessableContent());

        as(addMember(owner, "QUALITY_ENGINEER"), postJson(exception, Map.of("reason", "Mine")))
                .andExpect(status().isForbidden());
        as(manager, postJson(exception, Map.of("reason", "Company has ceased trading")))
                .andExpect(jsonPath("$.data.exceptionReason").value("Company has ceased trading"));
        requestClosure(owner, recall, true).andExpect(status().isOk());
    }

    @Test
    void aLinkedCapaMustCloseFirst() throws Exception {
        String capa = idOf(body(as(owner, postJson("/api/v1/capas", capaRequest())).andExpect(status().isCreated())));
        String recall = idOf(body(as(owner, postJson("/api/v1/recalls", Map.of(
                        "batchNumbers", List.of(numberOf(batch)), "recallDate", TODAY, "reason", "Cracks",
                        "instructions", "Return", "capaId", capa)))
                .andExpect(status().isCreated())));
        attempt(recall, "PHONE", "REACHED").andExpect(status().isCreated());
        acknowledge(recall).andExpect(status().isCreated());
        contactStatus(recall, "RESOLVED").andExpect(status().isOk());
        requestClosure(owner, recall, true).andExpect(status().isUnprocessableContent());
    }

    private Map<String, Object> capaRequest() {
        Map<String, Object> request = new HashMap<>(Map.of(
                "title", "Cracked shafts", "problemStatement", "Heat treatment", "ownerId", owner.userId().toString(),
                "targetDate", LocalDate.now().plusDays(30).toString(), "effectivenessRequired", false));
        request.put("ncrId", null);
        return request;
    }

    @Test
    void someoneWhoReceivesTheBatchAfterTheRecallStartsMustBeContactedToo() throws Exception {
        String partly = releasedBatch(releasedLot("10").id(), "10", "10");
        dispatch(customer, partly, "1", List.of()).andExpect(status().isCreated());
        batch = partly;
        String recall = resolved();
        String late = idOf(body(as(owner, postJson("/api/v1/customers", Map.of("code", code(), "name", "Bajaj Auto")))
                .andExpect(status().isCreated())));
        dispatch(late, partly, "1", List.of()).andExpect(status().isCreated());

        requestClosure(owner, recall, true).andExpect(status().isUnprocessableContent());

        as(owner, get("/api/v1/recalls/" + recall + "/customers")).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void permissionsTenantsAndFacilitiesAreEnforced() throws Exception {
        String recall = start();
        String path = "/api/v1/recalls/" + recall;
        Session viewer = addMember(owner, "VIEWER");
        as(viewer, get(path)).andExpect(status().isOk());
        as(viewer, postJson(path + "/close", Map.of())).andExpect(status().isForbidden());
        as(viewer, postJson("/api/v1/recalls", Map.of("batchNumbers", List.of("X"), "recallDate", TODAY,
                        "reason", "x", "instructions", "y")))
                .andExpect(status().isForbidden());
        as(addMember(owner, "QUALITY_INSPECTOR"), get(path)).andExpect(status().isForbidden());

        Session stranger = register();
        as(stranger, get(path)).andExpect(status().isNotFound());
        as(stranger, get(path + "/report.pdf")).andExpect(status().isNotFound());
        as(stranger, postJson(path + "/acknowledgements", Map.of("customerId", customer, "acknowledgedOn", TODAY,
                        "acknowledgedBy", "x")))
                .andExpect(status().isNotFound());
        as(stranger, get("/api/v1/recalls")).andExpect(jsonPath("$.data.totalItems").value(0));

        Session limited = addMember(owner, "QUALITY_MANAGER");
        String users = body(as(owner, get("/api/v1/users?size=100")));
        List<String> ids = JsonPath.read(users, "$.data.items[?(@.userId == '" + limited.userId() + "')].membershipId");
        as(owner, put("/api/v1/users/" + ids.getFirst() + "/facilities").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("facilityIds", List.of(facility(owner))))))
                .andExpect(status().isOk());
        as(limited, get(path)).andExpect(status().isNotFound());
        as(limited, get("/api/v1/recalls")).andExpect(jsonPath("$.data.totalItems").value(0));
    }

    @Test
    void thePdfCarriesTheReport() throws Exception {
        String recall = resolved();
        byte[] pdf = as(owner, get("/api/v1/recalls/" + recall + "/report.pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Product recall report", "Cracks found in the shaft", "Tata Motors",
                    numberOf(batch), "Page 1 of " + document.getNumberOfPages());
        }
    }
}
