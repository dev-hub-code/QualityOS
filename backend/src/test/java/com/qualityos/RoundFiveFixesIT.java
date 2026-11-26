package com.qualityos;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
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

/** What the fifth round of human testing found, kept from coming back. */
class RoundFiveFixesIT extends TraceabilityFixture {

    @Test
    void aYesNoCheckKeepsItsAnswerAsTrueOrFalseAndAYesPasses() throws Exception {
        String material = material(owner, true, "PCS");
        String template = JsonPath.read(body(as(owner, postJson("/api/v1/inspection-templates", Map.of(
                        "code", code(), "name", "Casting check", "inspectionType", "IQC", "materialId", material)))
                .andExpect(status().isCreated())), "$.data.template.id");
        String path = "/api/v1/inspection-templates/" + template;
        List<String> drafts = JsonPath.read(body(as(owner, get(path))), "$.data.versions[?(@.status == 'DRAFT')].id");
        Map<String, Object> sound = new HashMap<>(Map.of("code", "POROS", "name", "Free of porosity",
                "dataType", "BOOLEAN", "criticality", "CRITICAL", "mandatory", true, "perSample", true,
                "evidenceRequired", false, "expectedText", "maybe"));
        Map<String, Object> version = Map.of("samplingType", "FIXED", "sampleSize", 1, "acceptanceNumber", 0,
                "parameters", List.of(sound));
        as(owner, put(path + "/versions/" + drafts.getFirst()).contentType("application/json")
                        .content(json.writeValueAsString(version)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("must be Yes or No")));

        // What the editor's free-text field used to take.
        sound.put("expectedText", "Yes");
        as(owner, put(path + "/versions/" + drafts.getFirst()).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("samplingType", "FIXED", "sampleSize", 1,
                                "acceptanceNumber", 0, "parameters", List.of(sound)))))
                .andExpect(status().isOk());
        as(owner, get(path)).andExpect(jsonPath("$.data.versions[0].parameters[0].expectedText").value("true"));
        as(owner, postJson(path + "/publish", Map.of("effectiveFrom", TODAY))).andExpect(status().isOk());

        Lot lot = lot(material, "10");
        String inspection = idOf(body(as(owner, postJson("/api/v1/inspections",
                        Map.of("receiptItemId", lot.id(), "templateId", template)))
                .andExpect(status().isCreated())));
        as(owner, postJson("/api/v1/inspections/" + inspection + "/execute", Map.of("results", List.of(
                        Map.of("parameterCode", "POROS", "sampleNumber", 1, "valueBoolean", true)))))
                .andExpect(status().isOk());
        as(owner, postJson("/api/v1/inspections/" + inspection + "/submit", Map.of()))
                .andExpect(jsonPath("$.data.evaluatedResult").value("PASS"));
    }

    @Test
    void countedItemsComeInWholeNumbersWhereverAQuantityIsEntered() throws Exception {
        // A goods receipt, not only the order.
        String pieces = material(owner, false, "PCS");
        IssuedOrder order = issuedOrder(owner, pieces, "20");
        receive(owner, order, "10.5").andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("whole number")));
        String lot = JsonPath.read(body(receive(owner, order, "10").andExpect(status().isCreated())),
                "$.data.items[0].id");

        // The rejected quantity of an NCR.
        as(owner, postJson("/api/v1/ncrs", Map.of("title", "Porous castings", "description", "Two were porous",
                        "severity", "MINOR", "materialId", pieces, "rejectedQuantity", "2.5")))
                .andExpect(status().isUnprocessableContent());

        // A production batch, from planning to dispatch.
        String product = product();
        as(owner, postJson("/api/v1/production-batches", Map.of("productId", product, "facilityId", facility(owner),
                        "plannedQuantity", "2.5")))
                .andExpect(status().isUnprocessableContent());
        String batch = startBatch(product);
        consumeLot(batch, lot, "0.5").andExpect(status().isUnprocessableContent());
        consumeLot(batch, lot, "4").andExpect(status().isOk());
        complete(batch, "2.5").andExpect(status().isUnprocessableContent());
        complete(batch, "4").andExpect(status().isOk());
        inspectBatch(batch, "FQC", "20.0", "ACCEPT");
        dispatch(customer(), batch, "1.5", List.of()).andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("whole number")));
    }

    @Test
    void submittingASupplierTellsTheOtherSupplierManagers() throws Exception {
        Session reviewer = addMember(owner, "SUPPLIER_MANAGER");
        String supplier = draftSupplier(owner);
        as(owner, postJson("/api/v1/suppliers/" + supplier + "/submit", Map.of())).andExpect(status().isOk());
        as(reviewer, get("/api/v1/notifications"))
                .andExpect(jsonPath("$.data.items[*].type", hasItem("SUPPLIER_AWAITING_APPROVAL")));
    }

    @Test
    void aReportNamesItsPeriodInDays() throws Exception {
        as(owner, get("/api/v1/reports/ncr?from=2026-08-03&to=2026-10-31"))
                .andExpect(jsonPath("$.data.periodLabel").value("3 Aug 2026 to 31 Oct 2026"));
    }

    @Test
    void aMistypedRecordLinkFindsNothingRatherThanFailingValidation() throws Exception {
        as(owner, get("/api/v1/ncrs/new")).andExpect(status().isNotFound());
        as(owner, get("/api/v1/suppliers/12345")).andExpect(status().isNotFound());
    }
}
