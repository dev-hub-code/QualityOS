package com.qualityos;

import static org.hamcrest.Matchers.containsString;
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

    /** Resolving says why, since in most of these tests the customer still holds units. */
    private ResultActions contactStatus(String recall, String next) throws Exception {
        return as(owner, postJson("/api/v1/recalls/" + recall + "/customers/" + customer + "/status",
                "RESOLVED".equals(next) ? Map.of("status", next, "note", "Scrapped on site, certificate on file")
                        : Map.of("status", next)));
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
    void recallsAndDispatchesAreFoundByWhatTheySay() throws Exception {
        String recall = start();
        String recallNumber = JsonPath.read(body(as(owner, get("/api/v1/recalls/" + recall))), "$.data.recallNumber");
        String dispatchNumber = JsonPath.read(body(as(owner, get("/api/v1/dispatches"))),
                "$.data.items[0].dispatchNumber");
        for (String term : List.of(recallNumber.toLowerCase(), "CRACKS", numberOf(batch))) {
            as(owner, get("/api/v1/recalls").param("q", term))
                    .andExpect(jsonPath("$.data.items.length()").value(1))
                    .andExpect(jsonPath("$.data.items[0].id").value(recall));
        }
        for (String term : List.of(dispatchNumber, "tata")) {
            as(owner, get("/api/v1/dispatches").param("q", term))
                    .andExpect(jsonPath("$.data.items.length()").value(1))
                    .andExpect(jsonPath("$.data.items[0].id").value(dispatch));
        }
        // A wildcard is searched for as itself.
        as(owner, get("/api/v1/recalls").param("q", "%")).andExpect(jsonPath("$.data.items.length()").value(0));
        as(owner, get("/api/v1/dispatches").param("q", "Bajaj")).andExpect(jsonPath("$.data.items.length()").value(0));
        as(owner, get("/api/v1/dispatches")).andExpect(jsonPath("$.data.items.length()").value(1));
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
    void aSoleMemberClosesTheirOwnRecallWithAReason() throws Exception {
        String recall = resolved();
        returnAll();
        requestClosure(owner, recall, false).andExpect(status().isOk());
        String path = "/api/v1/recalls/" + recall + "/close";
        as(owner, postJson(path, Map.of())).andExpect(status().isUnprocessableContent());

        workAlone();
        as(owner, postJson(path, Map.of())).andExpect(status().isUnprocessableContent());
        as(owner, postJson(path, Map.of("note", "I am the only person here")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closureRequestedById").value(owner.userId().toString()));
        assertThat(selfReviews(owner.tenantId(), "RECALL_CLOSE")).isEqualTo(1);
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
    void aCustomerStillHoldingUnitsIsResolvedOnlyWithAReason() throws Exception {
        String recall = start();
        attempt(recall, "PHONE", "REACHED").andExpect(status().isCreated());
        acknowledge(recall).andExpect(status().isCreated());
        String path = "/api/v1/recalls/" + recall + "/customers/" + customer + "/status";
        as(owner, postJson(path, Map.of("status", "RESOLVED")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("are still with this customer")));
        as(owner, postJson(path, Map.of("status", "RESOLVED", "note", "Scrapped on site")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.notes").value(containsString("Scrapped on site")));
    }

    @Test
    void whatTheRegulatorWasToldCannotChangeOnceTheRecallIsClosed() throws Exception {
        String recall = resolved();
        regulatory(owner, recall, regulatoryBody(TODAY, 30)).andExpect(status().isOk());
        requestClosure(owner, recall, true).andExpect(status().isOk());
        as(manager, postJson("/api/v1/recalls/" + recall + "/close", Map.of())).andExpect(status().isOk());
        regulatory(owner, recall, regulatoryBody(TODAY, 15))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("is closed")));
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

    private ResultActions dispatchWithOverride(Session session, String to, String from, String reason)
            throws Exception {
        return as(session, postJson("/api/v1/dispatches", Map.of(
                "customerId", to, "dispatchedOn", TODAY, "recallOverrideReason", reason,
                "items", List.of(Map.of("productionBatchId", from, "quantity", "1", "serialNumbers", List.of())))));
    }

    @Test
    void goodsUnderRecallStayInUnlessSomeoneOverridesWithAReason() throws Exception {
        String partly = releasedBatch(releasedLot("10").id(), "10", "10");
        dispatch(customer, partly, "1", List.of()).andExpect(status().isCreated());
        batch = partly;
        String recall = resolved();
        String recallNumber = JsonPath.read(body(as(owner, get("/api/v1/recalls/" + recall))), "$.data.recallNumber");
        String late = idOf(body(as(owner, postJson("/api/v1/customers", Map.of("code", code(), "name", "Bajaj Auto")))
                .andExpect(status().isCreated())));

        dispatch(late, partly, "1", List.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.code").value("RECALL_HOLD"))
                .andExpect(jsonPath("$.error.message").value("Batch " + numberOf(partly) + " is under recall "
                        + recallNumber + ". Someone who can manage recalls may still dispatch it, with a reason."));
        dispatchWithOverride(owner, late, partly, "  ").andExpect(jsonPath("$.error.code").value("RECALL_HOLD"));

        String shipped = idOf(body(dispatchWithOverride(owner, late, partly, "Replacement units, checked by QA")
                .andExpect(status().isCreated())));
        assertThat(asDatabaseSuperuser().queryForList(
                "SELECT new_state ->> 'reason' FROM audit_logs WHERE action = 'RECALL_HOLD_OVERRIDDEN' AND entity_id = ?",
                String.class, shipped)).containsExactly("Replacement units, checked by QA");

        requestClosure(owner, recall, true).andExpect(status().isUnprocessableContent());
        as(owner, get("/api/v1/recalls/" + recall + "/customers")).andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void quantitiesOfDifferentUnitsAreShownSideBySideNotAdded() throws Exception {
        // Wire is made in metres and sold by the metre; coils of it are counted.
        String wire = idOf(body(as(owner, postJson("/api/v1/products", Map.of("code", code(), "name", "FR wire",
                        "productType", "SEMI_FINISHED", "uomId", unitId(owner, "M"))))
                .andExpect(status().isCreated())));
        String bulk = JsonPath.read(body(as(owner, postJson("/api/v1/production-batches", Map.of(
                        "productId", wire, "facilityId", facility(owner), "plannedQuantity", "500")))
                .andExpect(status().isCreated())), "$.data.batch.id");
        consumeLot(bulk, releasedLot("10").id(), "10").andExpect(status().isOk());
        complete(bulk, "500").andExpect(status().isOk());
        inspectBatch(bulk, "FQC", "20.0", "ACCEPT");
        String coils = startBatch(product());
        as(owner, postJson("/api/v1/production-batches/" + coils + "/consume",
                        Map.of("parentBatchId", bulk, "quantity", "450")))
                .andExpect(status().isOk());
        complete(coils, "5").andExpect(status().isOk());
        inspectBatch(coils, "FQC", "20.0", "ACCEPT");
        dispatch(customer, bulk, "40.5", List.of()).andExpect(status().isCreated());
        dispatch(customer, coils, "5", List.of()).andExpect(status().isCreated());
        batch = bulk;
        String recall = start();

        as(owner, get("/api/v1/recalls/" + recall + "/dashboard"))
                .andExpect(jsonPath("$.data.totals.outstanding").value(org.hamcrest.Matchers.allOf(
                        containsString("40.5 M"), containsString("5 PCS"))))
                .andExpect(jsonPath("$.data.totals.oneUnit").value(false));
        as(owner, get("/api/v1/recalls/" + recall))
                .andExpect(jsonPath("$.data.review.totals.dispatched").value(org.hamcrest.Matchers.allOf(
                        containsString("40.5 M"), containsString("5 PCS"))));
        as(owner, get("/api/v1/recalls/" + recall + "/customers"))
                .andExpect(jsonPath("$.data[0].totals.outstanding").value(org.hamcrest.Matchers.allOf(
                        containsString("40.5 M"), containsString("5 PCS"))));
    }

    @Test
    void whatIsMadeFromARecalledBatchIsHeldUntilTheRecallCloses() throws Exception {
        String component = releasedBatch(releasedLot("60").id(), "60", "30");
        String assembly = startBatch(product());
        as(owner, postJson("/api/v1/production-batches/" + assembly + "/consume",
                        Map.of("parentBatchId", component, "quantity", "20")))
                .andExpect(status().isOk());
        complete(assembly, "20").andExpect(status().isOk());
        inspectBatch(assembly, "FQC", "20.0", "ACCEPT");
        batch = component;
        String recall = start();

        dispatch(customer, assembly, "1", List.of()).andExpect(jsonPath("$.error.code").value("RECALL_HOLD"));

        requestClosure(owner, recall, false).andExpect(status().isOk());
        dispatch(customer, assembly, "1", List.of()).andExpect(jsonPath("$.error.code").value("RECALL_HOLD"));
        as(manager, postJson("/api/v1/recalls/" + recall + "/close", Map.of())).andExpect(status().isOk());
        dispatch(customer, assembly, "1", List.of()).andExpect(status().isCreated());
    }

    @Test
    void onlySomeoneWhoCanManageRecallsMayOverride() throws Exception {
        String partly = releasedBatch(releasedLot("10").id(), "10", "10");
        batch = partly;
        start();
        String role = idOf(body(as(owner, postJson("/api/v1/roles", Map.of(
                        "name", "Dispatch only " + code(), "permissions", List.of("TRACEABILITY_VIEW", "TRACEABILITY_MANAGE"))))
                .andExpect(status().isCreated())));
        Session clerk = addMember(owner, "VIEWER");
        as(owner, put("/api/v1/users/" + membershipId(owner, clerk.userId()) + "/roles").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("roleIds", List.of(role)))))
                .andExpect(status().isOk());

        dispatchWithOverride(clerk, customer, partly, "Customer insists")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.message").value(
                        "Only someone who can manage recalls may dispatch goods under recall."));
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

    @org.springframework.beans.factory.annotation.Autowired
    private com.qualityos.recall.service.RecallService recallService;

    private ResultActions regulatory(Session session, String recall, Map<String, Object> body) throws Exception {
        return as(session, put("/api/v1/recalls/" + recall + "/regulatory").contentType("application/json")
                .content(json.writeValueAsString(body)));
    }

    private Map<String, Object> regulatoryBody(String notifiedOn, int responseDays) {
        Map<String, Object> body = new HashMap<>(Map.of("hazardClass", "CLASS_II", "regulator", "FSSAI",
                "regulatorReference", "FSSAI/RC/2026/118", "responseDays", responseDays));
        body.put("regulatorNotifiedOn", notifiedOn);
        return body;
    }

    @Test
    void whatTheRegulatorIsToldIsKeptAndAudited() throws Exception {
        String recall = start();
        regulatory(owner, recall, regulatoryBody(TODAY, 5)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hazardClass").value("CLASS_II"))
                .andExpect(jsonPath("$.data.regulatorReference").value("FSSAI/RC/2026/118"))
                .andExpect(jsonPath("$.data.regulatorNotifiedOn").value(TODAY))
                .andExpect(jsonPath("$.data.responseDays").value(5));
        regulatory(owner, recall, regulatoryBody(LocalDate.now().minusDays(1).toString(), 5))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message")
                        .value("The regulator cannot have been told before the recall started."));
        regulatory(owner, recall, regulatoryBody(TODAY, 0)).andExpect(status().isBadRequest());
        regulatory(addMember(owner, "VIEWER"), recall, regulatoryBody(TODAY, 5)).andExpect(status().isForbidden());
        assertThat(inTransactionAs(owner.tenantId(), null, () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'RECALL_REGULATORY_UPDATED'", Long.class)))
                .isEqualTo(1);
    }

    @Test
    void theRegulatorsReportSaysWhatWasRecoveredAndWhoHasNotAnswered() throws Exception {
        String recall = start();
        regulatory(owner, recall, regulatoryBody(TODAY, 3)).andExpect(status().isOk());
        byte[] interim = as(owner, get("/api/v1/recalls/" + recall + "/regulatory-report.pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument document = Loader.loadPDF(interim)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Interim recall report for the regulator", "FSSAI/RC/2026/118",
                    "Class II: may cause temporary or", "Customers who have not acknowledged",
                    "Tata Motors", "0%", "No corrective action is linked yet", numberOf(batch), "Declaration");
        }

        attempt(recall, "PHONE", "REACHED").andExpect(status().isCreated());
        acknowledge(recall).andExpect(status().isCreated());
        returnAll();
        byte[] later = as(owner, get("/api/v1/recalls/" + recall + "/regulatory-report.pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument document = Loader.loadPDF(later)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("100%", "None. Every customer has acknowledged the recall");
        }
        Session stranger = register();
        as(stranger, get("/api/v1/recalls/" + recall + "/regulatory-report.pdf")).andExpect(status().isNotFound());
    }

    private int escalate() {
        return com.qualityos.common.tenant.TenantContext.callAs(owner.tenantId(), null,
                recallService::escalateUnanswered);
    }

    @Test
    void aCustomerSilentPastTheResponseTimeIsEscalatedOnce() throws Exception {
        String recall = start();
        String number = JsonPath.read(body(as(owner, get("/api/v1/recalls/" + recall))), "$.data.recallNumber");
        // Still within the three days.
        assertThat(escalate()).isZero();

        asAppRole(owner.tenantId(), sql -> sql.update(
                "UPDATE recalls SET recall_date = current_date - 4 WHERE id = ?::uuid", recall));
        asAppRole(null, sql -> assertThat(sql.queryForList("SELECT tenants_with_unanswered_recalls()",
                java.util.UUID.class)).contains(owner.tenantId()));
        // Being reached is not answering.
        attempt(recall, "PHONE", "REACHED").andExpect(status().isCreated());

        assertThat(escalate()).isEqualTo(1);
        assertThat(escalate()).isZero();
        String inbox = body(as(manager, get("/api/v1/notifications?size=50")));
        assertThat(JsonPath.<List<String>>read(inbox, "$.data.items[*].title"))
                .containsOnlyOnce(number + ": a customer has not acknowledged the recall");
        assertThat(JsonPath.<List<String>>read(inbox, "$.data.items[*].body").toString()).contains("Tata Motors");
        assertThat(inTransactionAs(owner.tenantId(), null, () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'RECALL_CUSTOMERS_ESCALATED'", Long.class)))
                .isEqualTo(1);
    }

    @Test
    void aCustomerWhoAnsweredInTimeIsNotEscalated() throws Exception {
        String recall = start();
        attempt(recall, "PHONE", "REACHED").andExpect(status().isCreated());
        acknowledge(recall).andExpect(status().isCreated());
        asAppRole(owner.tenantId(), sql -> sql.update(
                "UPDATE recalls SET recall_date = current_date - 10 WHERE id = ?::uuid", recall));
        assertThat(escalate()).isZero();
        asAppRole(null, sql -> assertThat(sql.queryForList("SELECT tenants_with_unanswered_recalls()",
                java.util.UUID.class)).doesNotContain(owner.tenantId()));
    }

    private ResultActions addBatches(Session session, String recall, String... numbers) throws Exception {
        return as(session, postJson("/api/v1/recalls/" + recall + "/batches", Map.of(
                "batchNumbers", List.of(numbers), "reason", "Same heat of steel")));
    }

    @Test
    void aBatchFoundLaterJoinsTheRecallAndItsCustomersAreAdded() throws Exception {
        String later = releasedBatch(releasedLot("50").id(), "50", "50");
        String other = customer();
        ship(idOf(body(dispatch(other, later, "50", List.of()).andExpect(status().isCreated())))).andExpect(status().isOk());
        String recall = start();
        as(owner, get("/api/v1/recalls/" + recall + "/customers")).andExpect(jsonPath("$.data.length()").value(1));

        addBatches(owner, recall, numberOf(later), numberOf(batch)).andExpect(status().isOk());
        as(owner, get("/api/v1/recalls/" + recall + "/customers")).andExpect(jsonPath("$.data.length()").value(2));
        addBatches(owner, recall, numberOf(later)).andExpect(status().isUnprocessableContent());
        addBatches(owner, recall, "NOPE").andExpect(status().isNotFound());
        addBatches(addMember(owner, "VIEWER"), recall, numberOf(later)).andExpect(status().isForbidden());

        String audit = body(as(owner, get("/api/v1/audit-logs?action=RECALL_BATCHES_ADDED")));
        assertThat(JsonPath.<List<String>>read(audit, "$.data.items[*].entityId")).containsExactly(recall);
        assertThat(audit).contains(numberOf(later)).doesNotContain("\"" + numberOf(batch) + "\"");
    }

    @Test
    void aClosedRecallTakesNoMoreBatches() throws Exception {
        String recall = resolved();
        requestClosure(owner, recall, true).andExpect(status().isOk());
        String later = releasedBatch(releasedLot("5").id(), "5", "5");
        addBatches(owner, recall, numberOf(later)).andExpect(status().isUnprocessableContent());
    }

    @Test
    void aCapaCanBeLinkedAfterTheRecallStartsAndThenMustCloseFirst() throws Exception {
        String recall = resolved();
        String capa = idOf(body(as(owner, postJson("/api/v1/capas", capaRequest())).andExpect(status().isCreated())));
        as(owner, put("/api/v1/recalls/" + recall + "/capa").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("capaId", capa))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capaId").value(capa));
        requestClosure(owner, recall, true).andExpect(status().isUnprocessableContent());
        as(owner, put("/api/v1/recalls/" + recall + "/capa").contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        requestClosure(owner, recall, true).andExpect(status().isOk());
    }
}
