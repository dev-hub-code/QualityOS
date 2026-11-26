package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

class StockTransferIT extends TraceabilityFixture {

    private static final String TRANSFERS = "/api/v1/stock-transfers";

    private ResultActions send(Session session, String from, String to, String key, String id, String quantity) throws Exception {
        return as(session, postJson(TRANSFERS, Map.of("fromFacilityId", from, "toFacilityId", to, "sentOn", TODAY,
                "items", List.of(Map.of(key, id, "quantity", quantity)))));
    }

    private ResultActions receive(Session session, String transfer) throws Exception {
        return as(session, postJson(TRANSFERS + "/" + transfer + "/receive", Map.of("receivedOn", TODAY)));
    }

    /** The lot as production sees it: its plant and what is left. */
    private Map<String, Object> lotView(String lot) throws Exception {
        List<Map<String, Object>> found = JsonPath.read(body(as(owner, get("/api/v1/material-receipts/released-lots"))),
                "$.data[?(@.itemId == '" + lot + "')]");
        return found.isEmpty() ? Map.of() : found.getFirst();
    }

    private String facilityOfBatch(String batch) throws Exception {
        return JsonPath.read(body(as(owner, get("/api/v1/production-batches/" + batch))), "$.data.batch.facilityId");
    }

    @Test
    void partOfALotIsInTransitThenALotOfItsOwnAtTheOtherPlantThatTracesFollow() throws Exception {
        Lot lot = releasedLot("10");
        String from = (String) lotView(lot.id()).get("facilityId");
        String to = facility(owner);

        String sent = body(send(owner, from, to, "lotId", lot.id(), "4").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.data.transferNumber").value(org.hamcrest.Matchers.startsWith("STR-")))
                .andExpect(jsonPath("$.data.items[0].kind").value("LOT"))
                .andExpect(jsonPath("$.data.items[0].sourceNumber").value(lot.batch())));
        String transfer = idOf(sent);
        // What is in transit has left: only six remain to use.
        assertThat(new java.math.BigDecimal((String) lotView(lot.id()).get("remainingQuantity"))).isEqualByComparingTo("6");
        String batch = startBatch(product());
        consumeLot(batch, lot.id(), "7").andExpect(status().isUnprocessableContent());

        String received = body(receive(owner, transfer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RECEIVED"))
                .andExpect(jsonPath("$.data.receivedOn").value(TODAY))
                .andExpect(jsonPath("$.data.items[0].receivedNumber").value(lot.batch() + "-T1")));
        String part = JsonPath.read(received, "$.data.items[0].receivedId");
        Map<String, Object> there = lotView(part);
        assertThat(there.get("facilityId")).isEqualTo(to);
        assertThat(new java.math.BigDecimal((String) there.get("remainingQuantity"))).isEqualByComparingTo("4");
        assertThat(there.get("released")).isEqualTo(true);
        receive(owner, transfer).andExpect(status().isUnprocessableContent());

        // Used at the other plant, it is still found from the lot it came from.
        consumeLot(batch, part, "4").andExpect(status().isOk());
        as(owner, get("/api/v1/traceability/batches/" + lot.batch()))
                .andExpect(jsonPath("$.data.forward[*].batchNumber").value(org.hamcrest.Matchers.hasItem(numberOf(batch))));
        // The new lot has a trace of its own, by its number.
        as(owner, get("/api/v1/traceability/batches/" + lot.batch() + "-T1")).andExpect(status().isOk());
    }

    @Test
    void aCorrectionOfWhatTheLotIsReachesThePartSentToAnotherPlant() throws Exception {
        Lot lot = releasedLot("10");
        String from = (String) lotView(lot.id()).get("facilityId");
        String transfer = idOf(body(send(owner, from, facility(owner), "lotId", lot.id(), "4")
                .andExpect(status().isCreated())));
        receive(owner, transfer).andExpect(status().isOk());

        String correction = idOf(body(as(owner, postJson("/api/v1/material-corrections", Map.of(
                "receiptItemId", lot.id(), "supplierBatch", "HEAT-99", "reason", "Heat number misread")))
                .andExpect(status().isCreated())));
        as(owner, postJson("/api/v1/material-corrections/" + correction + "/submit", Map.of())).andExpect(status().isOk());
        as(owner, get("/api/v1/material-corrections/" + correction))
                .andExpect(jsonPath("$.data.impact.transferredLots[0]").value(lot.batch() + "-T1"));
        as(manager, postJson("/api/v1/material-corrections/" + correction + "/approve",
                        Map.of("note", "Checked", "impactAcknowledged", true)))
                .andExpect(jsonPath("$.data.status").value("APPLIED"));

        as(owner, get("/api/v1/traceability/batches/" + lot.batch() + "-T1"))
                .andExpect(jsonPath("$.data.subject.supplierBatch").value("HEAT-99"));
    }

    @Test
    void partOfABatchIsReleasedAtTheOtherPlantAndADispatchLeavesFromOnePlant() throws Exception {
        String batch = releasedBatch(releasedLot("10").id(), "10", "10");
        String from = facilityOfBatch(batch);
        String to = facility(owner);
        String transfer = idOf(body(send(owner, from, to, "batchId", batch, "3").andExpect(status().isCreated())));
        String customer = customer();
        dispatch(customer, batch, "8", List.of()).andExpect(status().isUnprocessableContent());

        String received = body(receive(owner, transfer).andExpect(status().isOk()));
        String part = JsonPath.read(received, "$.data.items[0].receivedId");
        assertThat(numberOf(part)).isEqualTo(numberOf(batch) + "-T1");
        assertThat(statusOf(part)).isEqualTo("RELEASED");
        assertThat(facilityOfBatch(part)).isEqualTo(to);
        as(owner, get("/api/v1/traceability/batches/" + numberOf(batch)))
                .andExpect(jsonPath("$.data.forward[*].batchNumber").value(org.hamcrest.Matchers.hasItem(numberOf(part))));

        // One dispatch, one plant.
        as(owner, postJson("/api/v1/dispatches", Map.of("customerId", customer, "dispatchedOn", TODAY,
                        "items", List.of(Map.of("productionBatchId", batch, "quantity", "1"),
                                Map.of("productionBatchId", part, "quantity", "1")))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("one plant")));
        dispatch(customer, part, "3", List.of()).andExpect(status().isCreated());
        dispatch(customer, batch, "7", List.of()).andExpect(status().isCreated());
    }

    @Test
    void aCancelledTransferPutsTheStockBackAndCannotBeReceived() throws Exception {
        String batch = releasedBatch(releasedLot("10").id(), "10", "10");
        String from = facilityOfBatch(batch);
        String transfer = idOf(body(send(owner, from, facility(owner), "batchId", batch, "10")
                .andExpect(status().isCreated())));
        dispatch(customer(), batch, "1", List.of()).andExpect(status().isUnprocessableContent());

        as(owner, postJson(TRANSFERS + "/" + transfer + "/cancel", Map.of("reason", "Truck did not come")))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelReason").value("Truck did not come"));
        receive(owner, transfer).andExpect(status().isUnprocessableContent());
        dispatch(customer(), batch, "10", List.of()).andExpect(status().isCreated());
    }

    @Test
    void whatCannotBeSent() throws Exception {
        String material = material(owner, true);
        Lot pending = lot(material, "10");
        String lotPlant = JsonPath.read(body(as(owner, get("/api/v1/material-receipts/" + receiptOf(pending)))),
                "$.data.facilityId");
        String elsewhere = facility(owner);
        // Not yet released.
        send(owner, lotPlant, elsewhere, "lotId", pending.id(), "1").andExpect(status().isUnprocessableContent());
        String batch = releasedBatch(releasedLot("10").id(), "10", "10");
        String from = facilityOfBatch(batch);
        // To the same plant, from a plant it is not at, more than there is, or a line naming neither.
        send(owner, from, from, "batchId", batch, "1").andExpect(status().isUnprocessableContent());
        send(owner, elsewhere, from, "batchId", batch, "1").andExpect(status().isUnprocessableContent());
        send(owner, from, elsewhere, "batchId", batch, "11").andExpect(status().isUnprocessableContent());
        Map<String, Object> neither = new HashMap<>(Map.of("quantity", "1"));
        as(owner, postJson(TRANSFERS, Map.of("fromFacilityId", from, "toFacilityId", elsewhere, "sentOn", TODAY,
                "items", List.of(neither)))).andExpect(status().isUnprocessableContent());
        // Arriving before it left.
        String transfer = idOf(body(send(owner, from, elsewhere, "batchId", batch, "1").andExpect(status().isCreated())));
        as(owner, postJson(TRANSFERS + "/" + transfer + "/receive",
                Map.of("receivedOn", java.time.LocalDate.now().minusDays(1).toString())))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void eachPlantSeesItsTransfersAndOnlyTheReceivingPlantReceives() throws Exception {
        String batch = releasedBatch(releasedLot("10").id(), "10", "10");
        String from = facilityOfBatch(batch);
        String to = facility(owner);
        String transfer = idOf(body(send(owner, from, to, "batchId", batch, "2").andExpect(status().isCreated())));

        Session sender = addMember(owner, "PRODUCTION_MANAGER");
        limit(sender, from);
        as(sender, get(TRANSFERS)).andExpect(jsonPath("$.data.items[0].id").value(transfer));
        receive(sender, transfer).andExpect(status().isNotFound());

        Session receiver = addMember(owner, "PRODUCTION_MANAGER");
        limit(receiver, to);
        as(receiver, get(TRANSFERS + "/" + transfer)).andExpect(status().isOk());
        as(receiver, postJson(TRANSFERS + "/" + transfer + "/cancel", Map.of("reason", "x")))
                .andExpect(status().isNotFound());
        receive(receiver, transfer).andExpect(status().isOk());

        Session third = addMember(owner, "PRODUCTION_MANAGER");
        limit(third, facility(owner));
        as(third, get(TRANSFERS + "/" + transfer)).andExpect(status().isNotFound());
        as(third, get(TRANSFERS)).andExpect(jsonPath("$.data.items").isEmpty());
        as(register(), get(TRANSFERS + "/" + transfer)).andExpect(status().isNotFound());
    }

    private String receiptOf(Lot lot) throws Exception {
        List<String> ids = JsonPath.read(body(as(owner, get("/api/v1/material-receipts?size=100"))),
                "$.data.items[?(@.grnNumber)].id");
        for (String id : ids) {
            String detail = body(as(owner, get("/api/v1/material-receipts/" + id)));
            List<String> items = JsonPath.read(detail, "$.data.items[*].id");
            if (items.contains(lot.id())) {
                return id;
            }
        }
        throw new AssertionError("No receipt holds " + lot.id());
    }
}
