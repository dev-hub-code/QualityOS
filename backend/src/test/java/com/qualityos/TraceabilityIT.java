package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.ResultActions;

class TraceabilityIT extends TraceabilityFixture {

    @Test
    void aLotIsTracedForwardToItsCustomerAndAShipmentBackToItsSupplier() throws Exception {
        Lot lot = releasedLot("100");
        String component = releasedBatch(lot.id(), "60", "30");
        String assembly = startBatch(product());
        as(owner, postJson("/api/v1/production-batches/" + assembly + "/consume",
                        Map.of("parentBatchId", component, "quantity", "20")))
                .andExpect(status().isOk());
        complete(assembly, "20").andExpect(status().isOk());
        as(owner, postJson("/api/v1/production-batches/" + assembly + "/serials",
                        Map.of("serialNumbers", List.of("SN-" + lot.id(), "SN2-" + lot.id()))))
                .andExpect(status().isOk());
        inspectBatch(assembly, "FQC", "20.0", "ACCEPT");
        dispatch(customer(), assembly, "2", List.of("SN-" + lot.id(), "SN2-" + lot.id()))
                .andExpect(status().isCreated());

        String assemblyNumber = numberOf(assembly);
        String componentNumber = numberOf(component);
        as(owner, get("/api/v1/traceability/batches/" + lot.batch()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subject.kind").value("LOT"))
                .andExpect(jsonPath("$.data.subject.supplierBatch").value("HEAT-42"))
                .andExpect(jsonPath("$.data.subject.purchaseOrderNumber").isNotEmpty())
                .andExpect(jsonPath("$.data.forward[*].batchNumber")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(assemblyNumber, componentNumber)))
                .andExpect(jsonPath("$.data.affectedCustomers[0]").value("Tata Motors"));

        as(owner, get("/api/v1/traceability/batches/" + assemblyNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subject.kind").value("PRODUCTION_BATCH"))
                .andExpect(jsonPath("$.data.backward[*].batchNumber")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(lot.batch(), componentNumber)))
                .andExpect(jsonPath("$.data.edges.length()").value(2))
                .andExpect(jsonPath("$.data.dispatches.length()").value(1))
                .andExpect(jsonPath("$.data.timeline[?(@.kind == 'RECEIVED')]").isNotEmpty())
                .andExpect(jsonPath("$.data.timeline[?(@.kind == 'DISPATCHED')]").isNotEmpty());
    }

    @Test
    void anotherLotOfTheSameBatchIsNotDraggedIntoAForwardTrace() throws Exception {
        Lot traced = releasedLot("10");
        Lot other = releasedLot("10");
        String batch = startBatch(product());
        consumeLot(batch, traced.id(), "5").andExpect(status().isOk());
        consumeLot(batch, other.id(), "5").andExpect(status().isOk());

        as(owner, get("/api/v1/traceability/batches/" + traced.batch()))
                .andExpect(jsonPath("$.data.backward.length()").value(0))
                .andExpect(jsonPath("$.data.forward.length()").value(1))
                .andExpect(jsonPath("$.data.edges.length()").value(1));
    }

    @Test
    void everySearchFieldFindsTheBatch() throws Exception {
        String material = material(owner, false);
        String materialCode = JsonPath.read(body(as(owner, get("/api/v1/materials/" + material))), "$.data.material.code");
        Lot lot = lot(material, "10");
        String product = product();
        String productCode = JsonPath.read(body(as(owner, get("/api/v1/products/" + product))), "$.data.product.code");
        String batch = startBatch(product);
        consumeLot(batch, lot.id(), "5").andExpect(status().isOk());
        complete(batch, "5").andExpect(status().isOk());
        String serial = "SER-" + code();
        as(owner, postJson("/api/v1/production-batches/" + batch + "/serials", Map.of("serialNumbers", List.of(serial))))
                .andExpect(status().isOk());
        String number = numberOf(batch);

        for (String term : List.of(lot.batch(), "HEAT-42", materialCode)) {
            as(owner, get("/api/v1/traceability/search").param("q", term))
                    .andExpect(jsonPath("$.data[?(@.batchNumber == '" + lot.batch() + "')]").isNotEmpty());
        }
        for (String term : List.of(number, productCode, serial)) {
            as(owner, get("/api/v1/traceability/search").param("q", term))
                    .andExpect(jsonPath("$.data[?(@.batchNumber == '" + number + "')]").isNotEmpty());
        }
        as(owner, get("/api/v1/traceability/search").param("q", "x")).andExpect(status().isUnprocessableContent());
        as(owner, get("/api/v1/traceability/search").param("q", "%%%%")).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void uninspectedOrRejectedMaterialCannotEnterProduction() throws Exception {
        String inspected = material(owner, true);
        Lot pending = lot(inspected, "10");
        String batch = startBatch(product());
        consumeLot(batch, pending.id(), "1").andExpect(status().isUnprocessableContent());

        as(owner, get("/api/v1/material-receipts/released-lots"))
                .andExpect(jsonPath("$.data[?(@.itemId == '" + pending.id() + "')]").isEmpty());
    }

    @Test
    void aLotCannotBeOverdrawnAndAUsedReceiptCannotBeVoided() throws Exception {
        String material = material(owner, false);
        String received = body(receive(owner, issuedOrder(owner, material, "10"), "10").andExpect(status().isCreated()));
        String lot = JsonPath.read(received, "$.data.items[0].id");
        String batch = startBatch(product());
        consumeLot(batch, lot, "6").andExpect(status().isOk());
        consumeLot(batch, lot, "5").andExpect(status().isUnprocessableContent());

        as(owner, postJson("/api/v1/material-receipts/" + idOf(received) + "/void", Map.of("reason", "Mistake")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void aBatchNeedsItsInputsBeforeCompletion() throws Exception {
        complete(startBatch(product()), "5").andExpect(status().isUnprocessableContent());
    }

    @Test
    void onlyAReleasedBatchShipsOrFeedsAnother() throws Exception {
        Lot lot = releasedLot("10");
        String batch = startBatch(product());
        consumeLot(batch, lot.id(), "5").andExpect(status().isOk());
        complete(batch, "5").andExpect(status().isOk());
        String customer = customer();

        dispatch(customer, batch, "1", List.of()).andExpect(status().isUnprocessableContent());
        String next = startBatch(product());
        as(owner, postJson("/api/v1/production-batches/" + next + "/consume",
                        Map.of("parentBatchId", batch, "quantity", "1")))
                .andExpect(status().isUnprocessableContent());

        inspectBatch(batch, "FQC", "20.0", "ACCEPT");
        assertThat(statusOf(batch)).isEqualTo("RELEASED");
        dispatch(customer, batch, "6", List.of()).andExpect(status().isUnprocessableContent());
        dispatch(customer, batch, "5", List.of()).andExpect(status().isCreated());
        dispatch(customer, batch, "1", List.of()).andExpect(status().isUnprocessableContent());
    }

    @Test
    void aFailedFinalInspectionNeverReleasesAndRaisesAnNcrOnTheBatch() throws Exception {
        Lot lot = releasedLot("10");
        String batch = startBatch(product());
        consumeLot(batch, lot.id(), "5").andExpect(status().isOk());
        complete(batch, "5").andExpect(status().isOk());
        inspectBatch(batch, "FQC", "25", "REJECT");

        assertThat(statusOf(batch)).isEqualTo("REJECTED");
        dispatch(customer(), batch, "1", List.of()).andExpect(status().isUnprocessableContent());
        as(owner, get("/api/v1/traceability/batches/" + numberOf(batch)))
                .andExpect(jsonPath("$.data.inspections.length()").value(1))
                .andExpect(jsonPath("$.data.ncrs.length()").value(1))
                .andExpect(jsonPath("$.data.ncrs[0].severity").value("MAJOR"));
    }

    @Test
    void reworkHoldsABatchUntilItPassesAgain() throws Exception {
        Lot lot = releasedLot("10");
        String batch = startBatch(product());
        consumeLot(batch, lot.id(), "5").andExpect(status().isOk());

        inspectBatch(batch, "IPQC", "25", "REWORK");
        assertThat(statusOf(batch)).isEqualTo("ON_HOLD");
        complete(batch, "5").andExpect(status().isUnprocessableContent());

        inspectBatch(batch, "IPQC", "20.0", "ACCEPT");
        assertThat(statusOf(batch)).isEqualTo("IN_PROGRESS");
        complete(batch, "5").andExpect(status().isOk());
    }

    @Test
    void aBatchTakesOneInspectionAtATimeAndOnlyAtTheRightStage() throws Exception {
        Lot lot = releasedLot("10");
        String batch = startBatch(product());
        consumeLot(batch, lot.id(), "5").andExpect(status().isOk());
        inspect("productionBatchId", batch, "FQC").andExpect(status().isUnprocessableContent());
        inspect("productionBatchId", batch, "IQC").andExpect(status().isUnprocessableContent());
        inspect("productionBatchId", batch, "IPQC").andExpect(status().isCreated());
        inspect("productionBatchId", batch, "IPQC").andExpect(status().isConflict());
    }

    @Test
    void aSerialShipsOnceAndOnlyFromItsOwnBatch() throws Exception {
        Lot lot = releasedLot("10");
        String batch = startBatch(product());
        consumeLot(batch, lot.id(), "2").andExpect(status().isOk());
        complete(batch, "2").andExpect(status().isOk());
        String serial = "SER-" + code();
        String path = "/api/v1/production-batches/" + batch + "/serials";
        as(owner, postJson(path, Map.of("serialNumbers", List.of(serial)))).andExpect(status().isOk());
        as(owner, postJson(path, Map.of("serialNumbers", List.of(serial)))).andExpect(status().isConflict());
        as(owner, postJson(path, Map.of("serialNumbers", List.of("A-" + code(), "B-" + code()))))
                .andExpect(status().isUnprocessableContent());
        inspectBatch(batch, "FQC", "20.0", "ACCEPT");

        String customer = customer();
        dispatch(customer, batch, "1", List.of("UNKNOWN")).andExpect(status().isUnprocessableContent());
        dispatch(customer, batch, "1", List.of(serial)).andExpect(status().isCreated());
        dispatch(customer, batch, "1", List.of(serial)).andExpect(status().isUnprocessableContent());
    }

    @Test
    void aTraceHidesRecordsThePersonCouldNotOpen() throws Exception {
        Lot lot = releasedLot("10");
        String batch = startBatch(product());
        consumeLot(batch, lot.id(), "5").andExpect(status().isOk());
        complete(batch, "5").andExpect(status().isOk());
        inspectBatch(batch, "FQC", "25", "REJECT");

        String ncr = JsonPath.read(body(as(owner, get("/api/v1/traceability/batches/" + numberOf(batch)))),
                "$.data.ncrs[0].id");
        as(owner, postJson("/api/v1/capas", Map.of(
                        "ncrId", ncr, "title", "Fix", "problemStatement", "Oversize", "ownerId", owner.userId(),
                        "targetDate", LocalDate.now().plusDays(7).toString(), "effectivenessRequired", true)))
                .andExpect(status().isCreated());
        as(owner, get("/api/v1/traceability/batches/" + numberOf(batch)))
                .andExpect(jsonPath("$.data.capas.length()").value(1));

        // Inspectors can trace batches and read NCRs, but they have no access to CAPAs.
        Session inspector = addMember(owner, "QUALITY_INSPECTOR");
        as(inspector, get("/api/v1/traceability/batches/" + numberOf(batch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ncrs.length()").value(1))
                .andExpect(jsonPath("$.data.capas.length()").value(0))
                .andExpect(jsonPath("$.data.timeline[?(@.kind == 'CAPA')]").isEmpty());
    }

    @Test
    void viewersCannotRecordProduction() throws Exception {
        Session viewer = addMember(owner, "VIEWER");
        as(viewer, postJson("/api/v1/production-batches", Map.of(
                        "productId", product(), "facilityId", facility(owner), "plannedQuantity", "1")))
                .andExpect(status().isForbidden());
        as(viewer, postJson("/api/v1/customers", Map.of("code", "C", "name", "N"))).andExpect(status().isForbidden());
    }

    @Test
    void genealogyAndDispatchCannotBeRewritten() throws Exception {
        Lot lot = releasedLot("10");
        String batch = releasedBatch(lot.id(), "5", "5");
        dispatch(customer(), batch, "5", List.of()).andExpect(status().isCreated());

        for (String table : List.of("batch_relationships", "dispatch_items", "dispatch_records")) {
            assertThatThrownBy(() -> asAppRole(owner.tenantId(), statement ->
                            statement.update("DELETE FROM " + table)))
                    .as(table).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> asAppRole(owner.tenantId(), statement ->
                            statement.update("UPDATE " + table + " SET created_by = ?", UUID.randomUUID())))
                    .as(table).isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void aMistakenConsumptionIsReversedAndLeavesTheTrace() throws Exception {
        Lot wrong = releasedLot("10");
        Lot right = releasedLot("10");
        String batch = startBatch(product());
        String detail = body(consumeLot(batch, wrong.id(), "4").andExpect(status().isOk()));
        String entry = JsonPath.read(detail, "$.data.consumptions[0].id");
        String reverse = "/api/v1/production-batches/" + batch + "/consumptions/" + entry + "/reverse";

        as(owner, postJson(reverse, Map.of("reason", " "))).andExpect(status().isBadRequest());
        as(owner, postJson(reverse, Map.of("reason", "Wrong lot scanned")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consumptions[0].reversalReason").value("Wrong lot scanned"));
        as(owner, postJson(reverse, Map.of("reason", "Again"))).andExpect(status().isUnprocessableContent());

        // All 10 are usable again, and a batch with only reversed inputs still counts as having none.
        complete(batch, "1").andExpect(status().isUnprocessableContent());
        consumeLot(batch, wrong.id(), "10").andExpect(status().isOk());
        String later = JsonPath.read(body(as(owner, get("/api/v1/production-batches/" + batch))),
                "$.data.consumptions[1].id");
        as(owner, postJson("/api/v1/production-batches/" + batch + "/consumptions/" + later + "/reverse",
                        Map.of("reason", "Wrong lot again")))
                .andExpect(status().isOk());
        consumeLot(batch, right.id(), "4").andExpect(status().isOk());

        as(owner, get("/api/v1/traceability/batches/" + wrong.batch()))
                .andExpect(jsonPath("$.data.forward.length()").value(0));
        as(owner, get("/api/v1/traceability/batches/" + numberOf(batch)))
                .andExpect(jsonPath("$.data.backward.length()").value(1))
                .andExpect(jsonPath("$.data.backward[0].batchNumber").value(right.batch()));
    }

    @Test
    void aConsumptionCanOnlyBeReversedWhileTheBatchIsInProgress() throws Exception {
        Lot lot = releasedLot("10");
        String batch = startBatch(product());
        String entry = JsonPath.read(body(consumeLot(batch, lot.id(), "4")), "$.data.consumptions[0].id");
        complete(batch, "4").andExpect(status().isOk());
        as(owner, postJson("/api/v1/production-batches/" + batch + "/consumptions/" + entry + "/reverse",
                        Map.of("reason", "Too late")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void aReversedDispatchReturnsStockAndSerialsAndLeavesAffectedCustomers() throws Exception {
        Lot lot = releasedLot("10");
        String batch = startBatch(product());
        consumeLot(batch, lot.id(), "2").andExpect(status().isOk());
        complete(batch, "2").andExpect(status().isOk());
        String serial = "SER-" + code();
        as(owner, postJson("/api/v1/production-batches/" + batch + "/serials", Map.of("serialNumbers", List.of(serial))))
                .andExpect(status().isOk());
        inspectBatch(batch, "FQC", "20.0", "ACCEPT");
        String customer = customer();
        String shipped = body(dispatch(customer, batch, "1", List.of(serial)).andExpect(status().isCreated()));
        String reverse = "/api/v1/dispatches/" + idOf(shipped) + "/reverse";

        Session viewer = addMember(owner, "VIEWER");
        as(viewer, postJson(reverse, Map.of("reason", "Wrong truck"))).andExpect(status().isForbidden());
        as(owner, postJson(reverse, Map.of("reason", "Wrong truck")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reversalReason").value("Wrong truck"));
        as(owner, postJson(reverse, Map.of("reason", "Again"))).andExpect(status().isUnprocessableContent());

        as(owner, get("/api/v1/traceability/batches/" + numberOf(batch)))
                .andExpect(jsonPath("$.data.affectedCustomers.length()").value(0))
                .andExpect(jsonPath("$.data.dispatches[0].reversalReason").value("Wrong truck"))
                .andExpect(jsonPath("$.data.timeline[?(@.kind == 'DISPATCH_REVERSED')]").isNotEmpty());
        as(owner, get("/api/v1/production-batches/" + batch))
                .andExpect(jsonPath("$.data.batch.availableQuantity").value("2.000000"));
        dispatch(customer, batch, "1", List.of(serial)).andExpect(status().isCreated());
    }

    @Test
    void aDispatchCannotBeDatedInTheFuture() throws Exception {
        Lot lot = releasedLot("10");
        String batch = releasedBatch(lot.id(), "5", "5");
        // Two days ahead is in the future in every time zone an organisation can use.
        as(owner, postJson("/api/v1/dispatches", Map.of(
                        "customerId", customer(), "dispatchedOn", LocalDate.now().plusDays(2).toString(),
                        "items", List.of(Map.of("productionBatchId", batch, "quantity", "1")))))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void correctionsDoNotCrossTenantsOrChange() throws Exception {
        Lot lot = releasedLot("10");
        String batch = releasedBatch(lot.id(), "5", "5");
        String shipped = body(dispatch(customer(), batch, "1", List.of()).andExpect(status().isCreated()));
        String id = idOf(shipped);

        Session stranger = register();
        as(stranger, postJson("/api/v1/dispatches/" + id + "/reverse", Map.of("reason", "Mine")))
                .andExpect(status().isNotFound());
        as(owner, postJson("/api/v1/dispatches/" + id + "/reverse", Map.of("reason", "Mistake")))
                .andExpect(status().isOk());
        for (String sql : List.of("UPDATE trace_corrections SET reason = 'Rewritten'", "DELETE FROM trace_corrections")) {
            assertThatThrownBy(() -> asAppRole(owner.tenantId(), statement -> statement.update(sql)))
                    .as(sql).isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void anotherTenantSeesNoneOfThisAndCannotReachIt() throws Exception {
        Lot lot = releasedLot("10");
        String batch = releasedBatch(lot.id(), "5", "5");
        String number = numberOf(batch);
        String customer = customer();
        dispatch(customer, batch, "1", List.of()).andExpect(status().isCreated());

        Session stranger = register();
        as(stranger, get("/api/v1/production-batches/" + batch)).andExpect(status().isNotFound());
        as(stranger, get("/api/v1/production-batches")).andExpect(jsonPath("$.data.totalItems").value(0));
        as(stranger, get("/api/v1/traceability/batches/" + number)).andExpect(status().isNotFound());
        as(stranger, get("/api/v1/traceability/batches/" + lot.batch())).andExpect(status().isNotFound());
        as(stranger, get("/api/v1/traceability/search").param("q", number)).andExpect(jsonPath("$.data.length()").value(0));
        as(stranger, get("/api/v1/traceability/search").param("q", "HEAT-42")).andExpect(jsonPath("$.data.length()").value(0));
        as(stranger, get("/api/v1/customers")).andExpect(jsonPath("$.data.length()").value(0));
        as(stranger, get("/api/v1/dispatches")).andExpect(jsonPath("$.data.totalItems").value(0));
        as(stranger, get("/api/v1/material-receipts/released-lots")).andExpect(jsonPath("$.data.length()").value(0));

        Session attacker = stranger;
        String own = idOf(body(as(attacker, postJson("/api/v1/products", Map.of(
                        "code", code(), "name", "P", "productType", "FINISHED", "uomId", unitId(attacker, "PCS"))))
                .andExpect(status().isCreated())));
        String theirs = JsonPath.read(body(as(attacker, postJson("/api/v1/production-batches", Map.of(
                        "productId", own, "facilityId", facility(attacker), "plannedQuantity", "5")))
                .andExpect(status().isCreated())), "$.data.batch.id");
        as(attacker, postJson("/api/v1/production-batches/" + theirs + "/consume",
                        Map.of("receiptItemId", lot.id(), "quantity", "1")))
                .andExpect(status().isNotFound());
        as(attacker, postJson("/api/v1/production-batches/" + theirs + "/consume",
                        Map.of("parentBatchId", batch, "quantity", "1")))
                .andExpect(status().isNotFound());
        as(attacker, postJson("/api/v1/dispatches", Map.of("customerId", customer, "dispatchedOn", TODAY,
                        "items", List.of(Map.of("productionBatchId", batch, "quantity", "1")))))
                .andExpect(status().isUnprocessableContent());

        // Even with raw SQL, a batch here cannot record a lot that belongs to another organisation.
        assertThatThrownBy(() -> asAppRole(attacker.tenantId(), statement -> statement.update("""
                        INSERT INTO batch_relationships (id, tenant_id, parent_lot_id, child_batch_id, quantity)
                        VALUES (?, ?, ?, ?, 1)
                        """, UUID.randomUUID(), attacker.tenantId(), UUID.fromString(lot.id()), UUID.fromString(theirs))))
                .isInstanceOf(DataAccessException.class);
    }
}
