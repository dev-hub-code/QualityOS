package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;

/** A member limited to one plant cannot reach another's records, however they ask. */
class FacilityAccessIT extends TraceabilityFixture {

    private static final String FUTURE = LocalDate.now().plusDays(14).toString();

    private Session elsewhere;
    private String order;
    private String receipt;
    private String inspection;
    private String inspectionFacility;
    private String ncr;
    private String capa;
    private String batch;
    private String dispatch;
    private String reversal;

    @BeforeEach
    void records() throws Exception {
        elsewhere = addMember(owner, "ORGANISATION_ADMIN");
        limit(elsewhere, facility(owner));

        IssuedOrder issued = issuedOrder(owner, material(owner), "10");
        order = issued.id();
        String received = body(receive(owner, issued, "10").andExpect(status().isCreated()));
        receipt = idOf(received);
        String lot = JsonPath.read(received, "$.data.items[0].id");
        inspection = idOf(body(inspect("receiptItemId", lot, "IQC").andExpect(status().isCreated())));
        inspectionFacility = JsonPath.read(body(as(owner, get("/api/v1/purchase-orders/" + order))), "$.data.facilityId");
        String path = "/api/v1/inspections/" + inspection;
        as(owner, postJson(path + "/execute", Map.of("results", List.of(
                        Map.of("parameterCode", "DIA", "sampleNumber", 1, "valueNumeric", "25")))))
                .andExpect(status().isOk());
        as(owner, postJson(path + "/submit", Map.of())).andExpect(status().isOk());
        as(manager, postJson(path + "/approve", Map.of())).andExpect(status().isOk());
        as(manager, postJson(path + "/disposition", Map.of("disposition", "REJECT"))).andExpect(status().isOk());
        ncr = JsonPath.read(body(as(owner, get("/api/v1/ncrs").param("inspectionId", inspection))), "$.data.items[0].id");
        capa = idOf(body(as(owner, postJson("/api/v1/capas", Map.of(
                        "ncrId", ncr, "title", "Fix", "problemStatement", "Oversize", "ownerId", owner.userId().toString(),
                        "targetDate", FUTURE, "effectivenessRequired", true)))
                .andExpect(status().isCreated())));

        batch = releasedBatch(releasedLot("5").id(), "5", "5");
        dispatch = idOf(body(dispatch(customer(), batch, "5", List.of()).andExpect(status().isCreated())));
        ship(dispatch).andExpect(status().isOk());
        reversal = idOf(body(as(owner, postJson("/api/v1/dispatches/" + dispatch + "/reversals", Map.of(
                        "returnReference", "RMA-1", "reason", "Damaged", "reversalDate", TODAY, "expected", true,
                        "items", List.of(Map.of("quantity", "1", "dispatchItemId", line(), "serialNumbers", List.of())))))
                .andExpect(status().isCreated())));
    }

    private String line() throws Exception {
        return JsonPath.read(body(as(owner, get("/api/v1/dispatches"))), "$.data.items[0].lines[0].id");
    }

    private void limitToInspectionFacility(Session member) throws Exception {
        limit(member, inspectionFacility);
    }

    @Test
    void listsLeaveOutOtherPlants() throws Exception {
        for (String path : List.of("/purchase-orders", "/material-receipts", "/material-receipts/awaiting-inspection",
                "/inspections", "/ncrs", "/capas", "/dispatches", "/dispatch-reversals", "/material-corrections")) {
            as(elsewhere, get("/api/v1" + path)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalItems").value(0));
        }
        assertThat(JsonPath.<List<?>>read(body(as(elsewhere, get("/api/v1/material-receipts/released-lots"))), "$.data"))
                .isEmpty();
    }

    @Test
    void anotherPlantsRecordsLookAsIfTheyDoNotExist() throws Exception {
        for (String path : List.of("/purchase-orders/" + order, "/material-receipts/" + receipt,
                "/inspections/" + inspection, "/ncrs/" + ncr, "/capas/" + capa,
                "/dispatches/" + dispatch + "/reversals", "/dispatches/" + dispatch + "/reversal-summary",
                "/dispatch-reversals/" + reversal)) {
            as(elsewhere, get("/api/v1" + path)).andExpect(status().isNotFound());
            as(owner, get("/api/v1" + path)).andExpect(status().isOk());
        }
    }

    @Test
    void anotherPlantsRecordsCannotBeChanged() throws Exception {
        for (String path : List.of("/purchase-orders/" + order + "/close", "/inspections/" + inspection + "/cancel",
                "/ncrs/" + ncr + "/containment", "/capas/" + capa + "/start-analysis",
                "/dispatch-reversals/" + reversal + "/cancel")) {
            as(elsewhere, postJson("/api/v1" + path, Map.of("containmentAction", "x")))
                    .andExpect(status().isNotFound());
        }
        as(elsewhere, postJson("/api/v1/dispatches/" + dispatch + "/reverse", Map.of("reason", "x")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherPlantsLotsAndBatchesCannotBeUsed() throws Exception {
        String lot = releasedLot("5").id();
        as(elsewhere, postJson("/api/v1/inspections", Map.of("receiptItemId", lot, "templateId", template("IQC"))))
                .andExpect(status().isNotFound());
        as(elsewhere, postJson("/api/v1/dispatches", Map.of("customerId", customer(), "dispatchedOn", TODAY,
                        "items", List.of(Map.of("productionBatchId", batch, "quantity", "1", "serialNumbers", List.of())))))
                .andExpect(status().isNotFound());
        as(elsewhere, postJson("/api/v1/material-corrections", Map.of(
                        "receiptItemId", lot, "reason", "Wrong", "supplierBatch", "HEAT-9")))
                .andExpect(status().isNotFound());
    }

    @Test
    void attachmentsFollowTheirRecord() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "note.pdf", "application/pdf", "%PDF-1.4".getBytes());
        for (String[] parent : List.of(new String[] {"NCR", ncr}, new String[] {"CAPA", capa},
                new String[] {"MATERIAL_RECEIPT", receipt})) {
            as(elsewhere, get("/api/v1/attachments").param("entityType", parent[0]).param("entityId", parent[1]))
                    .andExpect(status().isNotFound());
            mvc.perform(multipart("/api/v1/attachments").file(file)
                            .param("entityType", parent[0]).param("entityId", parent[1])
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + elsewhere.accessToken()))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void aMemberAtThePlantKeepsWorking() throws Exception {
        Session local = addMember(owner, "QUALITY_MANAGER");
        limitToInspectionFacility(local);
        as(local, get("/api/v1/inspections/" + inspection)).andExpect(status().isOk());
        as(local, get("/api/v1/ncrs/" + ncr)).andExpect(status().isOk());
        as(local, get("/api/v1/capas/" + capa)).andExpect(status().isOk());
        as(local, get("/api/v1/inspections")).andExpect(jsonPath("$.data.totalItems").value(1));
        as(local, get("/api/v1/ncrs")).andExpect(jsonPath("$.data.totalItems").value(1));
        as(local, get("/api/v1/capas")).andExpect(jsonPath("$.data.totalItems").value(1));
    }

    @Test
    void aRecordWithNoFacilityStaysWithUnlimitedMembers() throws Exception {
        String manual = idOf(body(as(owner, postJson("/api/v1/ncrs", Map.of(
                        "title", "Burrs", "description", "Line 2", "severity", "MINOR")))
                .andExpect(status().isCreated())));
        as(owner, get("/api/v1/ncrs/" + manual)).andExpect(status().isOk());
        as(elsewhere, get("/api/v1/ncrs/" + manual)).andExpect(status().isNotFound());
    }

    @Test
    void aLimitedMemberMustPlaceWhatTheyRaise() throws Exception {
        as(elsewhere, postJson("/api/v1/ncrs", Map.of("title", "Burrs", "description", "Line 2", "severity", "MINOR")))
                .andExpect(status().isUnprocessableContent());
        as(elsewhere, postJson("/api/v1/ncrs", Map.of("title", "Burrs", "description", "Line 2", "severity", "MINOR",
                        "facilityId", inspectionFacility)))
                .andExpect(status().isNotFound());
        as(elsewhere, postJson("/api/v1/capas", Map.of("title", "Fix", "problemStatement", "Oversize",
                        "ownerId", elsewhere.userId().toString(), "targetDate", FUTURE, "effectivenessRequired", true)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void workCannotBeGivenToSomeoneWhoCannotOpenIt() throws Exception {
        as(owner, postJson("/api/v1/ncrs/" + ncr + "/assign", Map.of("assigneeId", elsewhere.userId().toString())))
                .andExpect(status().isUnprocessableContent());
        as(owner, postJson("/api/v1/capas", Map.of("ncrId", ncr, "title", "Fix", "problemStatement", "Oversize",
                        "ownerId", elsewhere.userId().toString(), "targetDate", FUTURE, "effectivenessRequired", true)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void nobodyIsToldAboutAnotherPlantsRecords() throws Exception {
        String notifications = body(as(elsewhere, get("/api/v1/notifications")));
        assertThat(notifications).doesNotContain(inspection).doesNotContain(ncr).doesNotContain(capa);
        assertThat(body(as(manager, get("/api/v1/notifications")))).contains(inspection);
    }
}
