package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.ResultActions;

class MaterialCorrectionIT extends TraceabilityFixture {

    private static final String PATH = "/api/v1/material-corrections";

    private ResultActions request(Session session, Map<String, Object> changes) throws Exception {
        Map<String, Object> body = new HashMap<>(changes);
        body.putIfAbsent("reason", "Entered from the wrong delivery note");
        return as(session, postJson(PATH, body));
    }

    private String submitted(Map<String, Object> changes) throws Exception {
        String id = idOf(body(request(owner, changes).andExpect(status().isCreated())));
        as(owner, postJson(PATH + "/" + id + "/submit", Map.of())).andExpect(status().isOk());
        return id;
    }

    private ResultActions approve(Session session, String id, boolean acknowledged) throws Exception {
        return as(session, postJson(PATH + "/" + id + "/approve",
                Map.of("note", "Checked", "impactAcknowledged", acknowledged)));
    }

    private String entry(String batch, int index) throws Exception {
        return JsonPath.read(body(as(owner, get("/api/v1/production-batches/" + batch))),
                "$.data.consumptions[" + index + "].id");
    }

    private String completedBatch(Lot lot) throws Exception {
        String batch = startBatch(product());
        consumeLot(batch, lot.id(), "4").andExpect(status().isOk());
        complete(batch, "4").andExpect(status().isOk());
        return batch;
    }

    private void assertRemaining(Lot lot, String expected) throws Exception {
        as(owner, get("/api/v1/material-receipts/released-lots"))
                .andExpect(jsonPath("$.data[?(@.itemId == '" + lot.id() + "')].remainingQuantity").value(expected));
    }

    @Test
    void aWrongLotOnACompletedBatchIsCorrectedThroughApproval() throws Exception {
        Lot wrong = releasedLot("10");
        Lot right = releasedLot("10");
        String batch = completedBatch(wrong);
        String number = numberOf(batch);

        // The quick reversal stays closed once the batch is complete.
        as(owner, postJson("/api/v1/production-batches/" + batch + "/consumptions/" + entry(batch, 0) + "/reverse",
                        Map.of("reason", "Too late")))
                .andExpect(status().isUnprocessableContent());

        String id = submitted(Map.of("consumptionId", entry(batch, 0), "correctedLotId", right.id()));
        as(owner, get(PATH + "/" + id))
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.changes[0].field").value("SOURCE_LOT"))
                .andExpect(jsonPath("$.data.changes[0].originalValue").value(wrong.batch()))
                .andExpect(jsonPath("$.data.changes[0].correctedValue").value(right.batch()));

        // Nothing moves until it is approved.
        as(owner, get("/api/v1/traceability/batches/" + number))
                .andExpect(jsonPath("$.data.backward[0].batchNumber").value(wrong.batch()));
        approve(owner, id, false).andExpect(status().isUnprocessableContent());

        approve(manager, id, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.approvals[0].note").value("Checked"));

        as(owner, get("/api/v1/traceability/batches/" + number))
                .andExpect(jsonPath("$.data.backward.length()").value(1))
                .andExpect(jsonPath("$.data.backward[0].batchNumber").value(right.batch()))
                .andExpect(jsonPath("$.data.timeline[?(@.kind == 'CORRECTED')]").isNotEmpty());
        assertRemaining(wrong, "10.000000");
        assertRemaining(right, "6.000000");

        // History keeps the original, on both the batch and the wrongly recorded lot.
        as(owner, get("/api/v1/batches/" + batch + "/correction-history"))
                .andExpect(jsonPath("$.data[0].changes[0].originalValue").value(wrong.batch()));
        as(owner, get("/api/v1/batches/" + wrong.id() + "/correction-history"))
                .andExpect(jsonPath("$.data.length()").value(1));
        as(owner, get("/api/v1/production-batches/" + batch))
                .andExpect(jsonPath("$.data.consumptions[0].reversalReason").isNotEmpty())
                .andExpect(jsonPath("$.data.consumptions[1].sourceBatch").value(right.batch()));
        as(owner, get("/api/v1/audit-logs")).andExpect(jsonPath("$.data.items[*].action",
                hasItem("MATERIAL_CORRECTION_APPLIED")));
    }

    @Test
    void aCorrectionAlsoWorksBeforeCompletion() throws Exception {
        Lot lot = releasedLot("10");
        String batch = startBatch(product());
        consumeLot(batch, lot.id(), "4").andExpect(status().isOk());

        String id = submitted(Map.of("consumptionId", entry(batch, 0), "quantity", "7"));
        approve(manager, id, true).andExpect(jsonPath("$.data.status").value("APPLIED"));
        assertRemaining(lot, "3.000000");
    }

    @Test
    void aRejectedCorrectionChangesNothing() throws Exception {
        Lot lot = releasedLot("10");
        String id = submitted(Map.of("receiptItemId", lot.id(), "supplierBatch", "HEAT-99"));

        as(owner, postJson(PATH + "/" + id + "/reject", Map.of("note", "Mine"))).andExpect(status().isUnprocessableContent());
        as(manager, postJson(PATH + "/" + id + "/reject", Map.of("note", "Note is right")))
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.decisionNote").value("Note is right"));
        approve(manager, id, false).andExpect(status().isUnprocessableContent());
        as(owner, get("/api/v1/traceability/batches/" + lot.batch()))
                .andExpect(jsonPath("$.data.subject.supplierBatch").value("HEAT-42"));
    }

    @Test
    void anUnusedLotNeedsOneApproverAndUpdatesTheTrace() throws Exception {
        Lot lot = releasedLot("10");
        String id = submitted(Map.of("receiptItemId", lot.id(), "supplierBatch", "HEAT-99"));
        as(owner, get(PATH + "/" + id)).andExpect(jsonPath("$.data.approvalsNeeded").value(1));
        approve(manager, id, false).andExpect(jsonPath("$.data.status").value("APPLIED"));

        as(owner, get("/api/v1/traceability/batches/" + lot.batch()))
                .andExpect(jsonPath("$.data.subject.supplierBatch").value("HEAT-99"));
        as(owner, get("/api/v1/batches/" + lot.id() + "/correction-history"))
                .andExpect(jsonPath("$.data[0].changes[0].originalValue").value("HEAT-42"));
    }

    @Test
    void materialThatHasGoneDownstreamNeedsTwoApproversAndAnAcknowledgement() throws Exception {
        Lot lot = releasedLot("10");
        Lot right = releasedLot("10");
        String component = releasedBatch(lot.id(), "4", "4");
        dispatch(customer(), component, "1", List.of()).andExpect(status().isCreated());

        String id = submitted(Map.of("consumptionId", entry(component, 0), "correctedLotId", right.id()));
        as(owner, get(PATH + "/" + id))
                .andExpect(jsonPath("$.data.approvalsNeeded").value(2))
                .andExpect(jsonPath("$.data.impact.customers[0]").value("Tata Motors"));

        approve(manager, id, false).andExpect(status().isUnprocessableContent());
        approve(manager, id, true).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));
        approve(manager, id, true).andExpect(status().isUnprocessableContent());

        Session plant = addMember(owner, "PLANT_MANAGER");
        approve(plant, id, true).andExpect(jsonPath("$.data.status").value("APPLIED"));

        // The downstream trace now reaches the right lot, and the customer stays on it.
        as(owner, get("/api/v1/traceability/batches/" + right.batch()))
                .andExpect(jsonPath("$.data.affectedCustomers[0]").value("Tata Motors"));
        as(owner, get("/api/v1/traceability/batches/" + lot.batch()))
                .andExpect(jsonPath("$.data.affectedCustomers.length()").value(0));
    }

    @Test
    void aCorrectionAfterDownstreamConsumptionFollowsTheChain() throws Exception {
        Lot lot = releasedLot("10");
        Lot right = releasedLot("10");
        String component = releasedBatch(lot.id(), "4", "4");
        String assembly = startBatch(product());
        as(owner, postJson("/api/v1/production-batches/" + assembly + "/consume",
                        Map.of("parentBatchId", component, "quantity", "2")))
                .andExpect(status().isOk());

        String id = submitted(Map.of("consumptionId", entry(component, 0), "correctedLotId", right.id()));
        Session plant = addMember(owner, "PLANT_MANAGER");
        approve(manager, id, true).andExpect(status().isOk());
        approve(plant, id, true).andExpect(jsonPath("$.data.status").value("APPLIED"));

        as(owner, get("/api/v1/traceability/batches/" + numberOf(assembly)))
                .andExpect(jsonPath("$.data.backward[*].batchNumber", hasItem(right.batch())));
    }

    @Test
    void aQuantityCorrectionMovesThePurchaseOrderAndCannotGoBelowWhatWasUsed() throws Exception {
        String material = material(owner, false);
        IssuedOrder order = issuedOrder(owner, material, "100");
        String received = body(receive(owner, order, "50").andExpect(status().isCreated()));
        String lot = JsonPath.read(received, "$.data.items[0].id");

        approve(manager, submitted(Map.of("receiptItemId", lot, "quantity", "40")), false)
                .andExpect(jsonPath("$.data.status").value("APPLIED"));
        String po = body(as(owner, get("/api/v1/purchase-orders/" + order.id())));
        assertThat(new java.math.BigDecimal(JsonPath.read(po, "$.data.items[0].receivedQuantity").toString()))
                .isEqualByComparingTo("40");

        String batch = startBatch(product());
        consumeLot(batch, lot, "30").andExpect(status().isOk());
        String tooLow = submitted(Map.of("receiptItemId", lot, "quantity", "20"));
        approve(manager, tooLow, true).andExpect(status().isOk());
        Session plant = addMember(owner, "PLANT_MANAGER");
        approve(plant, tooLow, true).andExpect(status().isUnprocessableContent());

        // Everything rolled back, so the correction still waits for its last approval.
        as(owner, get(PATH + "/" + tooLow)).andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));
    }

    @Test
    void aSupplierOrMaterialCorrectionReachesTheInspection() throws Exception {
        String material = material(owner, true);
        Lot lot = lot(material, "10");
        String inspection = idOf(body(inspect("receiptItemId", lot.id(), "IQC").andExpect(status().isCreated())));
        String other = approvedSupplier(owner);

        String id = submitted(Map.of("receiptItemId", lot.id(), "supplierId", other));
        as(owner, get(PATH + "/" + id)).andExpect(jsonPath("$.data.approvalsNeeded").value(2));
        Session plant = addMember(owner, "PLANT_MANAGER");
        approve(manager, id, true).andExpect(status().isOk());
        approve(plant, id, true).andExpect(jsonPath("$.data.status").value("APPLIED"));

        as(owner, get("/api/v1/inspections").param("supplierId", other))
                .andExpect(jsonPath("$.data.items[0].id").value(inspection));
    }

    @Test
    void onlyOneCorrectionIsOpenAndAnOutdatedOneCannotApply() throws Exception {
        Lot lot = releasedLot("10");
        String first = submitted(Map.of("receiptItemId", lot.id(), "supplierBatch", "HEAT-1"));
        request(owner, Map.of("receiptItemId", lot.id(), "supplierBatch", "HEAT-2")).andExpect(status().isConflict());
        approve(manager, first, false).andExpect(status().isOk());
        request(owner, Map.of("receiptItemId", lot.id(), "supplierBatch", "HEAT-1"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void theRightPeopleRequestAndApprove() throws Exception {
        Lot lot = releasedLot("10");
        Session inspector = addMember(owner, "QUALITY_INSPECTOR");
        Session engineer = addMember(owner, "QUALITY_ENGINEER");
        request(inspector, Map.of("receiptItemId", lot.id(), "supplierBatch", "X")).andExpect(status().isForbidden());

        String id = idOf(body(request(engineer, Map.of("receiptItemId", lot.id(), "supplierBatch", "X"))
                .andExpect(status().isCreated())));
        as(owner, postJson(PATH + "/" + id + "/submit", Map.of())).andExpect(status().isUnprocessableContent());
        as(engineer, postJson(PATH + "/" + id + "/submit", Map.of())).andExpect(status().isOk());
        approve(engineer, id, false).andExpect(status().isForbidden());

        as(manager, get("/api/v1/notifications")).andExpect(jsonPath("$.data.items[*].type",
                hasItem("MATERIAL_CORRECTION_AWAITING_APPROVAL")));
        approve(manager, id, false).andExpect(status().isOk());
        as(engineer, get("/api/v1/notifications")).andExpect(jsonPath("$.data.items[*].type",
                hasItem("MATERIAL_CORRECTION_DECIDED")));
    }

    @Test
    void aRequesterCanCancelBeforeItIsDecided() throws Exception {
        Lot lot = releasedLot("10");
        String id = submitted(Map.of("receiptItemId", lot.id(), "supplierBatch", "X"));
        as(owner, postJson(PATH + "/" + id + "/cancel", Map.of("note", "Found the note")))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        approve(manager, id, false).andExpect(status().isUnprocessableContent());
    }

    @Test
    void anotherTenantCannotSeeOrReachACorrection() throws Exception {
        Lot lot = releasedLot("10");
        String id = submitted(Map.of("receiptItemId", lot.id(), "supplierBatch", "X"));
        Session stranger = register();

        as(stranger, get(PATH + "/" + id)).andExpect(status().isNotFound());
        as(stranger, get(PATH)).andExpect(jsonPath("$.data.totalItems").value(0));
        as(stranger, get("/api/v1/batches/" + lot.id() + "/correction-history"))
                .andExpect(jsonPath("$.data.length()").value(0));
        approve(stranger, id, false).andExpect(status().isNotFound());
        request(stranger, Map.of("receiptItemId", lot.id(), "supplierBatch", "Y")).andExpect(status().isNotFound());
    }

    @Test
    void appliedCorrectionsCannotBeRewritten() throws Exception {
        Lot lot = releasedLot("10");
        String id = submitted(Map.of("receiptItemId", lot.id(), "supplierBatch", "X"));
        approve(manager, id, false).andExpect(status().isOk());

        for (String sql : List.of(
                "UPDATE material_corrections SET reason = 'Rewritten'",
                "DELETE FROM material_corrections",
                "UPDATE material_correction_items SET original_value = 'Rewritten'",
                "DELETE FROM material_correction_items",
                "UPDATE material_correction_approvals SET note = 'Rewritten'",
                "DELETE FROM material_correction_approvals")) {
            assertThatThrownBy(() -> asAppRole(owner.tenantId(), statement -> statement.update(sql)))
                    .as(sql).isInstanceOf(DataAccessException.class);
        }
    }
}
