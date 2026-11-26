package com.qualityos;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What the third round of human testing found, kept from coming back. */
class RoundThreeFixesIT extends TraceabilityFixture {

    @Test
    void cancellingAnInspectionOrAnIssuedOrderNeedsAReason() throws Exception {
        Lot lot = lot(material(owner, true, "PCS"), "10");
        String inspection = idOf(body(inspect("receiptItemId", lot.id(), "IQC").andExpect(status().isCreated())));
        as(owner, postJson("/api/v1/inspections/" + inspection + "/cancel", Map.of()))
                .andExpect(status().isUnprocessableContent());
        as(owner, postJson("/api/v1/inspections/" + inspection + "/cancel", Map.of("reason", "Wrong lot")))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        IssuedOrder order = issuedOrder(owner, "5");
        as(owner, postJson("/api/v1/purchase-orders/" + order.id() + "/cancel", Map.of()))
                .andExpect(status().isUnprocessableContent());
        as(owner, postJson("/api/v1/purchase-orders/" + order.id() + "/cancel", Map.of("reason", "Supplier withdrew")))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    void aGstinMustCarryTheRightCheckCharacter() throws Exception {
        as(owner, postJson("/api/v1/customers", Map.of("code", code(), "name", "Bad", "gstin", "27AAKCD4321M1Z9")))
                .andExpect(status().isBadRequest());
        as(owner, postJson("/api/v1/customers", Map.of("code", code(), "name", "Good", "gstin", "27AAKCD4321M1ZG")))
                .andExpect(status().isCreated());
    }

    @Test
    void withoutThePermissionAMalformedBodyIsRefusedNotValidated() throws Exception {
        Session viewer = addMember(owner, "VIEWER");
        as(viewer, postJson("/api/v1/instruments", Map.of())).andExpect(status().isForbidden());
        as(viewer, postJson("/api/v1/customers", Map.of())).andExpect(status().isForbidden());
    }

    @Test
    void aWeighedLotCannotBeSampledByCountingAndAFixedSampleIsNoLargerThanTheLot() throws Exception {
        String counted = template("IQC");
        setSampling(counted, Map.of("samplingType", "PERCENTAGE", "samplePercent", "10", "acceptanceNumber", 0));
        Lot weighed = lot(material(owner, true, "KG"), "500");
        as(owner, postJson("/api/v1/inspections", Map.of("receiptItemId", weighed.id(), "templateId", counted)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("measured in KG")));

        String fixed = template("IQC");
        setSampling(fixed, Map.of("samplingType", "FIXED", "sampleSize", 5, "acceptanceNumber", 0));
        Lot small = lot(material(owner, true, "PCS"), "2");
        as(owner, postJson("/api/v1/inspections", Map.of("receiptItemId", small.id(), "templateId", fixed)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sampleSize").value(2));
    }

    @Test
    void aBatchTrackedBySerialNumberShipsByThem() throws Exception {
        String batch = startBatch(product());
        consumeLot(batch, releasedLot("10").id(), "10").andExpect(status().isOk());
        String prefix = "R3-" + code();
        complete(batch, "2").andExpect(status().isOk());
        as(owner, postJson("/api/v1/production-batches/" + batch + "/serials",
                        Map.of("serialNumbers", List.of(prefix + "-1", prefix + "-2"))))
                .andExpect(status().isOk());
        inspectBatch(batch, "FQC", "20.0", "ACCEPT");
        String customer = customer();
        dispatch(customer, batch, "1", List.of()).andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("serial number")));
        dispatch(customer, batch, "1", List.of(prefix + "-1")).andExpect(status().isCreated());
    }

    /** Publishes a new version of the template with this sampling, keeping its parameter. */
    private void setSampling(String template, Map<String, Object> sampling) throws Exception {
        String path = "/api/v1/inspection-templates/" + template;
        as(owner, postJson(path + "/versions", Map.of())).andExpect(status().is2xxSuccessful());
        List<String> drafts = JsonPath.read(body(as(owner, get(path))), "$.data.versions[?(@.status == 'DRAFT')].id");
        Map<String, Object> version = new HashMap<>(sampling);
        version.put("parameters", List.of(Map.of(
                "code", "DIA", "name", "DIA", "dataType", "NUMERIC", "criticality", "MAJOR",
                "mandatory", true, "perSample", true, "evidenceRequired", false,
                "minValue", "19.9", "maxValue", "20.1")));
        as(owner, put(path + "/versions/" + drafts.getFirst()).contentType("application/json")
                        .content(json.writeValueAsString(version)))
                .andExpect(status().isOk());
        as(owner, postJson(path + "/publish", Map.of("effectiveFrom", TODAY))).andExpect(status().isOk());
    }
}
