package com.qualityos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.web.servlet.ResultActions;

/** An organisation with an owner and a manager, and the steps that take material through production. */
abstract class TraceabilityFixture extends MasterDataFixture {

    protected static final String TODAY = LocalDate.now().toString();

    protected Session owner;
    protected Session manager;

    @BeforeEach
    void organisation() throws Exception {
        owner = register();
        manager = addMember(owner, "QUALITY_MANAGER");
    }

    protected record Lot(String id, String batch) {
    }

    /** A lot that needs no inspection, so it is released on arrival. */
    protected Lot releasedLot(String quantity) throws Exception {
        return lot(material(owner, false), quantity);
    }

    protected Lot lot(String material, String quantity) throws Exception {
        String received = body(receive(owner, issuedOrder(owner, material, quantity), quantity)
                .andExpect(status().isCreated()));
        return new Lot(JsonPath.read(received, "$.data.items[0].id"),
                JsonPath.read(received, "$.data.items[0].internalBatch"));
    }

    protected String product() throws Exception {
        return idOf(body(as(owner, postJson("/api/v1/products", Map.of(
                        "code", code(), "name", "Drive Shaft", "productType", "FINISHED",
                        "uomId", unitId(owner, "PCS"))))
                .andExpect(status().isCreated())));
    }

    protected String startBatch(String product) throws Exception {
        return JsonPath.read(body(as(owner, postJson("/api/v1/production-batches", Map.of(
                        "productId", product, "facilityId", facility(owner), "plannedQuantity", "50")))
                .andExpect(status().isCreated())), "$.data.batch.id");
    }

    protected ResultActions consumeLot(String batch, String lot, String quantity) throws Exception {
        return as(owner, postJson("/api/v1/production-batches/" + batch + "/consume",
                Map.of("receiptItemId", lot, "quantity", quantity)));
    }

    protected ResultActions complete(String batch, String quantity) throws Exception {
        return as(owner, postJson("/api/v1/production-batches/" + batch + "/complete",
                Map.of("producedQuantity", quantity)));
    }

    protected String template(String type) throws Exception {
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

    protected ResultActions inspect(String subjectKey, String subject, String type) throws Exception {
        return as(owner, postJson("/api/v1/inspections", Map.of(subjectKey, subject, "templateId", template(type))));
    }

    /** Inspects a production batch: the owner measures, and the manager decides. */
    protected String inspectBatch(String batch, String type, String value, String disposition) throws Exception {
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

    protected String releasedBatch(String lot, String lotQuantity, String produced) throws Exception {
        String batch = startBatch(product());
        consumeLot(batch, lot, lotQuantity).andExpect(status().isOk());
        complete(batch, produced).andExpect(status().isOk());
        inspectBatch(batch, "FQC", "20.0", "ACCEPT");
        return batch;
    }

    protected String customer() throws Exception {
        return idOf(body(as(owner, postJson("/api/v1/customers", Map.of("code", code(), "name", "Tata Motors")))
                .andExpect(status().isCreated())));
    }

    protected ResultActions dispatch(String customer, String batch, String quantity, List<String> serials)
            throws Exception {
        return as(owner, postJson("/api/v1/dispatches", Map.of(
                "customerId", customer, "dispatchedOn", TODAY,
                "items", List.of(Map.of("productionBatchId", batch, "quantity", quantity, "serialNumbers", serials)))));
    }

    protected String numberOf(String batch) throws Exception {
        return JsonPath.read(body(as(owner, get("/api/v1/production-batches/" + batch))), "$.data.batch.batchNumber");
    }

    protected String statusOf(String batch) throws Exception {
        return JsonPath.read(body(as(owner, get("/api/v1/production-batches/" + batch))), "$.data.batch.status");
    }
}
