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

/** What the fourth round of human testing found, kept from coming back. */
class RoundFourFixesIT extends TraceabilityFixture {

    @Test
    void aGstinMustBeRegisteredInTheStateGivenWithIt() throws Exception {
        // 27 is Maharashtra.
        as(owner, postJson("/api/v1/facilities", Map.of("code", code(), "name", "Vapi", "state", "Gujarat",
                        "gstin", "27AAKCD4321M1ZG")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("registered in Maharashtra, not in Gujarat")));
        as(owner, postJson("/api/v1/facilities", Map.of("code", code(), "name", "Pune", "state", "Maharashtra",
                        "gstin", "27AAKCD4321M1ZG")))
                .andExpect(status().isCreated());
        as(owner, postJson("/api/v1/suppliers", Map.of("code", code(), "name", "Garden City", "state", "Karnataka",
                        "gstin", "27AAKCD4321M1ZG")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void shiftsOfAFacilityCannotOverlapOrLastNoTime() throws Exception {
        String shifts = "/api/v1/facilities/" + facility(owner) + "/shifts";
        as(owner, postJson(shifts, Map.of("name", "A", "startsAt", "06:00", "endsAt", "14:00"))).andExpect(status().isCreated());
        as(owner, postJson(shifts, Map.of("name", "Night", "startsAt", "22:00", "endsAt", "06:00"))).andExpect(status().isCreated());
        as(owner, postJson(shifts, Map.of("name", "Same", "startsAt", "09:00", "endsAt", "09:00")))
                .andExpect(status().isUnprocessableContent());
        as(owner, postJson(shifts, Map.of("name", "Overlap", "startsAt", "13:00", "endsAt", "17:00")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("overlaps shift A")));
        // Past midnight into the morning shift.
        as(owner, postJson(shifts, Map.of("name", "Late", "startsAt", "14:00", "endsAt", "23:00")))
                .andExpect(status().isUnprocessableContent());
        as(owner, postJson(shifts, Map.of("name", "B", "startsAt", "14:00", "endsAt", "22:00", "breakMinutes", 30)))
                .andExpect(status().isCreated());
        // On Sundays only, it does not meet a shift that works Monday to Saturday.
        String weekday = "/api/v1/facilities/" + facility(owner) + "/shifts";
        as(owner, postJson(weekday, Map.of("name", "Week", "startsAt", "08:00", "endsAt", "16:00", "daysOfWeek", 63)))
                .andExpect(status().isCreated());
        as(owner, postJson(weekday, Map.of("name", "Sunday", "startsAt", "08:00", "endsAt", "16:00", "daysOfWeek", 64)))
                .andExpect(status().isCreated());
    }

    @Test
    void codesDifferingOnlyInCaseAreTheSameCode() throws Exception {
        String code = "TIO2-" + code();
        Map<String, Object> material = Map.of("code", code, "name", "Titanium dioxide", "uomId", unitId(owner, "KG"),
                "inspectionRequired", false);
        as(owner, postJson("/api/v1/materials", material)).andExpect(status().isCreated());
        Map<String, Object> lower = new HashMap<>(material);
        lower.put("code", code.toLowerCase(java.util.Locale.ROOT));
        as(owner, postJson("/api/v1/materials", lower)).andExpect(status().isConflict());
    }

    @Test
    void theSubmitterCannotRejectTheirOwnSupplierAndAnEditedIdentityIsApprovedAgain() throws Exception {
        String pending = draftSupplier(owner);
        as(owner, postJson("/api/v1/suppliers/" + pending + "/submit", Map.of())).andExpect(status().isOk());
        as(owner, postJson("/api/v1/suppliers/" + pending + "/reject", Map.of("note", "Changed my mind")))
                .andExpect(status().isUnprocessableContent());

        String approved = approvedSupplier(owner);
        String path = "/api/v1/suppliers/" + approved;
        Number version = JsonPath.read(body(as(owner, get(path))), "$.data.supplier.version");
        // A new phone number is not a new supplier.
        as(owner, put(path).contentType("application/json").content(json.writeValueAsString(Map.of(
                        "code", "ignored", "name", "Bharat Steels", "phone", "020 2345 6789", "version", version))))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        version = JsonPath.read(body(as(owner, get(path))), "$.data.supplier.version");
        as(owner, put(path).contentType("application/json").content(json.writeValueAsString(Map.of(
                        "code", "ignored", "name", "Titan Pigments", "version", version))))
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));
        as(owner, get(path)).andExpect(jsonPath("$.data.supplier.statusNote").value(containsString("approving again")));
        as(addMember(owner, "SUPPLIER_MANAGER"), postJson(path + "/approve", Map.of()))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    void aMaterialIsListedInAUnitOfItsOwnKindAndCountedOnesInWholeNumbers() throws Exception {
        String weighed = material(owner, false, "KG");
        as(owner, postJson("/api/v1/products/" + product() + "/materials", Map.of(
                        "materialId", weighed, "quantityPerUnit", "5.2", "uomId", unitId(owner, "LTR"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("is kept in KG")));

        String counted = material(owner, false, "PCS");
        as(owner, postJson("/api/v1/purchase-orders", orderRequest(approvedSupplier(owner), facility(owner), counted,
                        "10.5", null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("whole number")));
        as(owner, postJson("/api/v1/purchase-orders", orderRequest(approvedSupplier(owner), facility(owner), counted,
                        "10", null)))
                .andExpect(status().isCreated());
    }

    @Test
    void aCountingSamplePlanIsNotPublishedForAWeighedItemNorAnAqlYesNoCheckWithoutItsAnswer() throws Exception {
        String weighed = material(owner, true, "KG");
        String bulk = draftTemplate(Map.of("materialId", weighed));
        setVersion(bulk, aql(numeric()));
        publish(bulk).andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("measured in KG")));

        String counted = draftTemplate(Map.of("materialId", material(owner, true, "PCS")));
        Map<String, Object> lid = new HashMap<>(Map.of("code", "SEAL", "name", "Lid seals", "dataType", "BOOLEAN",
                "criticality", "MAJOR", "mandatory", true, "perSample", true, "evidenceRequired", false));
        setVersion(counted, aql(lid));
        publish(counted).andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("expected answer of SEAL")));
        lid.put("expectedText", "true");
        setVersion(counted, aql(lid));
        publish(counted).andExpect(status().isOk());
    }

    @Test
    void anNcrNamesOnlyABatchOnRecordAndShowsWhyACapaWasLinked() throws Exception {
        Map<String, Object> ncr = new HashMap<>(Map.of("title", "Leaking pails", "description", "Two leaked",
                "severity", "MINOR", "batchNumber", "LOT-9999-00001"));
        as(owner, postJson("/api/v1/ncrs", ncr)).andExpect(status().isUnprocessableContent())
                .andExpect(content().string(containsString("No lot or batch LOT-9999-00001")));
        ncr.put("batchNumber", releasedLot("5").batch());
        String first = idOf(body(as(owner, postJson("/api/v1/ncrs", ncr)).andExpect(status().isCreated())));
        ncr.remove("batchNumber");
        String second = idOf(body(as(owner, postJson("/api/v1/ncrs", ncr)).andExpect(status().isCreated())));

        String capa = idOf(body(as(owner, postJson("/api/v1/capas", Map.of("ncrId", first, "title", "Seal lids",
                        "problemStatement", "Lids leak", "ownerId", owner.userId().toString(),
                        "targetDate", java.time.LocalDate.now().plusDays(30).toString(), "effectivenessRequired", false)))
                .andExpect(status().isCreated())));
        as(owner, postJson("/api/v1/ncrs/" + second + "/capas", Map.of("capaId", capa, "note", "Same lid batch")))
                .andExpect(jsonPath("$.data.capas[0].linkNote").value("Same lid batch"));
        as(owner, get("/api/v1/capas/" + capa))
                .andExpect(jsonPath("$.data.linkedNcrs[0].id").value(second))
                .andExpect(jsonPath("$.data.linkedNcrs[0].note").value("Same lid batch"));
    }

    private String draftTemplate(Map<String, Object> target) throws Exception {
        Map<String, Object> request = new HashMap<>(Map.of("code", code(), "name", "Incoming check", "inspectionType", "IQC"));
        request.putAll(target);
        return JsonPath.read(body(as(owner, postJson("/api/v1/inspection-templates", request))
                .andExpect(status().isCreated())), "$.data.template.id");
    }

    private void setVersion(String template, Map<String, Object> version) throws Exception {
        String path = "/api/v1/inspection-templates/" + template;
        List<String> drafts = JsonPath.read(body(as(owner, get(path))), "$.data.versions[?(@.status == 'DRAFT')].id");
        as(owner, put(path + "/versions/" + drafts.getFirst()).contentType("application/json")
                        .content(json.writeValueAsString(version)))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions publish(String template) throws Exception {
        return as(owner, postJson("/api/v1/inspection-templates/" + template + "/publish", Map.of("effectiveFrom", TODAY)));
    }

    private static Map<String, Object> aql(Map<String, Object> parameter) {
        return Map.of("samplingType", "AQL", "aql", "4.0", "inspectionLevel", "II", "acceptanceNumber", 0,
                "parameters", List.of(parameter));
    }

    private static Map<String, Object> numeric() {
        return Map.of("code", "DIA", "name", "DIA", "dataType", "NUMERIC", "criticality", "MAJOR", "mandatory", true,
                "perSample", true, "evidenceRequired", false, "minValue", "19.9", "maxValue", "20.1");
    }
}
