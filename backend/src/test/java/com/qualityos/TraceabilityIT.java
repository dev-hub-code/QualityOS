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

class TraceabilityIT extends MasterDataFixture {

    private static final String TODAY = LocalDate.now().toString();

    private Session owner;
    private Session manager;

    @BeforeEach
    void organisation() throws Exception {
        owner = register();
        manager = addMember(owner, "QUALITY_MANAGER");
    }

    private record Lot(String id, String batch) {
    }

    /** A lot that needs no inspection, so it is released on arrival. */
    private Lot releasedLot(String quantity) throws Exception {
        return lot(material(owner, false), quantity);
    }

    private Lot lot(String material, String quantity) throws Exception {
        String received = body(receive(owner, issuedOrder(owner, material, quantity), quantity)
                .andExpect(status().isCreated()));
        return new Lot(JsonPath.read(received, "$.data.items[0].id"),
                JsonPath.read(received, "$.data.items[0].internalBatch"));
    }

    private String product() throws Exception {
        return idOf(body(as(owner, postJson("/api/v1/products", Map.of(
                        "code", code(), "name", "Drive Shaft", "productType", "FINISHED",
                        "uomId", unitId(owner, "PCS"))))
                .andExpect(status().isCreated())));
    }

    private String startBatch(String product) throws Exception {
        return JsonPath.read(body(as(owner, postJson("/api/v1/production-batches", Map.of(
                        "productId", product, "facilityId", facility(owner), "plannedQuantity", "50")))
                .andExpect(status().isCreated())), "$.data.batch.id");
    }

    private ResultActions consumeLot(String batch, String lot, String quantity) throws Exception {
        return as(owner, postJson("/api/v1/production-batches/" + batch + "/consume",
                Map.of("receiptItemId", lot, "quantity", quantity)));
    }

    private ResultActions complete(String batch, String quantity) throws Exception {
        return as(owner, postJson("/api/v1/production-batches/" + batch + "/complete",
                Map.of("producedQuantity", quantity)));
    }

    private String template(String type) throws Exception {
        String template = JsonPath.read(body(as(owner, postJson("/api/v1/inspection-templates",
                        Map.of("code", code(), "name", "Shaft check", "inspectionType", type)))
                .andExpect(status().isCreated())), "$.data.template.id");
        List<String> drafts = JsonPath.read(body(as(owner, get("/api/v1/inspection-templates/" + template))),
                "$.data.versions[?(@.status == 'DRAFT')].id");
        Map<String, Object> parameter = new HashMap<>(Map.of(
                "code", "DIA", "name", "DIA", "dataType", "NUMERIC", "criticality", "MAJOR",
                "mandatory", true, "perSample", true, "evidenceRequired", false,
                "minValue", "19.9", "maxValue", "20.1"));
        as(owner, put("/api/v1/inspection-templates/" + template + "/versions/" + drafts.getFirst())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("samplingType", "FIXED", "sampleSize", 1,
                                "acceptanceNumber", 0, "parameters", List.of(parameter)))))
                .andExpect(status().isOk());
        as(owner, postJson("/api/v1/inspection-templates/" + template + "/publish", Map.of("effectiveFrom", TODAY)))
                .andExpect(status().isOk());
        return template;
    }

    private ResultActions inspect(String subjectKey, String subject, String type) throws Exception {
        return as(owner, postJson("/api/v1/inspections", Map.of(subjectKey, subject, "templateId", template(type))));
    }

    /** Inspects a production batch: the owner measures, and the manager decides. */
    private String inspectBatch(String batch, String type, String value, String disposition) throws Exception {
        String inspection = idOf(body(inspect("productionBatchId", batch, type).andExpect(status().isCreated())));
        String path = "/api/v1/inspections/" + inspection;
        as(owner, postJson(path + "/execute", Map.of("results", List.of(
                        Map.of("parameterCode", "DIA", "sampleNumber", 1, "valueNumeric", value)))))
                .andExpect(status().isOk());
        as(owner, postJson(path + "/submit", Map.of())).andExpect(status().isOk());
        as(manager, postJson(path + "/approve", Map.of())).andExpect(status().isOk());
        as(manager, postJson(path + "/disposition", Map.of("disposition", disposition))).andExpect(status().isOk());
        return inspection;
    }

    private String releasedBatch(String lot, String lotQuantity, String produced) throws Exception {
        String batch = startBatch(product());
        consumeLot(batch, lot, lotQuantity).andExpect(status().isOk());
        complete(batch, produced).andExpect(status().isOk());
        inspectBatch(batch, "FQC", "20.0", "ACCEPT");
        return batch;
    }

    private String customer() throws Exception {
        return idOf(body(as(owner, postJson("/api/v1/customers", Map.of("code", code(), "name", "Tata Motors")))
                .andExpect(status().isCreated())));
    }

    private ResultActions dispatch(String customer, String batch, String quantity, List<String> serials)
            throws Exception {
        return as(owner, postJson("/api/v1/dispatches", Map.of(
                "customerId", customer, "dispatchedOn", TODAY,
                "items", List.of(Map.of("productionBatchId", batch, "quantity", quantity, "serialNumbers", serials)))));
    }

    private String numberOf(String batch) throws Exception {
        return JsonPath.read(body(as(owner, get("/api/v1/production-batches/" + batch))), "$.data.batch.batchNumber");
    }

    private String statusOf(String batch) throws Exception {
        return JsonPath.read(body(as(owner, get("/api/v1/production-batches/" + batch))), "$.data.batch.status");
    }

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
