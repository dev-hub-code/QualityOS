package com.qualityos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Double sampling. AQL 4.0 at level II on lots of 100 is letter F: two samples of 13, the first accepting 0 and
 * rejecting from 3, both together accepting 3 and rejecting from 4.
 */
class DoubleSamplingIT extends MasterDataFixture {

    private Session owner;
    private Session manager;
    private String template;
    private String supplier;
    private String material;
    private String facility;

    @BeforeEach
    void series() throws Exception {
        owner = register();
        manager = addMember(owner, "QUALITY_MANAGER");
        material = material(owner, true, "PCS"); // AQL samples count units, so the lots are counted
        supplier = approvedSupplier(owner);
        facility = facility(owner);
        template = JsonPath.read(body(as(owner, postJson("/api/v1/inspection-templates",
                        Map.of("code", code(), "name", "Bar IQC", "inspectionType", "IQC")))
                .andExpect(status().isCreated())), "$.data.template.id");
        List<String> drafts = JsonPath.read(body(as(owner, get("/api/v1/inspection-templates/" + template))),
                "$.data.versions[?(@.status == 'DRAFT')].id");
        Map<String, Object> parameter = new HashMap<>(Map.of("code", "DIA", "name", "DIA",
                "dataType", "NUMERIC", "criticality", "MAJOR", "mandatory", true, "perSample", true,
                "evidenceRequired", false));
        parameter.put("minValue", "19.9");
        parameter.put("maxValue", "20.1");
        as(owner, put("/api/v1/inspection-templates/" + template + "/versions/" + drafts.getFirst())
                .contentType("application/json").content(json.writeValueAsString(Map.of("samplingType", "AQL",
                        "aql", "4.0", "inspectionLevel", "II", "acceptanceNumber", 0, "doubleSampling", true,
                        "parameters", List.of(parameter)))))
                .andExpect(status().isOk());
        as(owner, postJson("/api/v1/inspection-templates/" + template + "/publish",
                Map.of("effectiveFrom", java.time.LocalDate.now().toString()))).andExpect(status().isOk());
    }

    private String start() throws Exception {
        String order = body(as(owner, postJson("/api/v1/purchase-orders",
                        orderRequest(supplier, facility, material, "100", null)))
                .andExpect(status().isCreated()));
        String orderId = idOf(order);
        as(owner, postJson("/api/v1/purchase-orders/" + orderId + "/issue", Map.of())).andExpect(status().isOk());
        String item = JsonPath.read(body(receive(owner, new IssuedOrder(orderId, JsonPath.read(order,
                "$.data.items[0].id")), "100").andExpect(status().isCreated())), "$.data.items[0].id");
        return idOf(body(as(owner, postJson("/api/v1/inspections", Map.of("receiptItemId", item, "templateId",
                template))).andExpect(status().isCreated())));
    }

    /** Records samples {@code from} to {@code to}, the first {@code nonconforming} of them out of tolerance. */
    private ResultActions record(String inspection, int from, int to, int nonconforming) throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        for (int sample = from; sample <= to; sample++) {
            results.add(Map.of("parameterCode", "DIA", "sampleNumber", sample,
                    "valueNumeric", sample < from + nonconforming ? "21" : "20"));
        }
        return as(owner, postJson("/api/v1/inspections/" + inspection + "/execute", Map.of("results", results)));
    }

    private ResultActions submit(String inspection) throws Exception {
        return as(owner, postJson("/api/v1/inspections/" + inspection + "/submit", Map.of()));
    }

    private ResultActions approve(String inspection) throws Exception {
        return as(manager, postJson("/api/v1/inspections/" + inspection + "/approve", Map.of()));
    }

    private ResultActions state() throws Exception {
        return as(owner, get("/api/v1/inspection-templates/" + template + "/aql-switching"));
    }

    @Test
    void aClearFirstSampleDecidesTheLotAlone() throws Exception {
        as(owner, get("/api/v1/inspection-templates/" + template))
                .andExpect(jsonPath("$.data.versions[0].doubleSampling").value(true));
        String good = start();
        as(owner, get("/api/v1/inspections/" + good))
                .andExpect(jsonPath("$.data.sampleSize").value(13))
                .andExpect(jsonPath("$.data.secondSampleDrawn").value(false))
                .andExpect(jsonPath("$.data.specification.codeLetter").value("F"))
                .andExpect(jsonPath("$.data.specification.acceptanceNumber").value(0))
                .andExpect(jsonPath("$.data.specification.rejectionNumber").value(3))
                .andExpect(jsonPath("$.data.specification.secondSample.sampleSize").value(13))
                .andExpect(jsonPath("$.data.specification.secondSample.acceptanceNumber").value(3))
                .andExpect(jsonPath("$.data.specification.secondSample.rejectionNumber").value(4));
        // The second sample's units cannot be recorded until it is drawn.
        record(good, 14, 14, 0).andExpect(status().isUnprocessableContent());
        record(good, 1, 13, 0).andExpect(status().isOk());
        submit(good).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.evaluatedResult").value("PASS"));

        // The database allows the sample to grow only by drawing the second sample, by its size.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> asDatabaseSuperuser().update(
                "UPDATE inspections SET sample_size = sample_size + 13 WHERE id = ?::uuid", good))
                .hasMessageContaining("cannot be changed");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> asDatabaseSuperuser().update(
                "UPDATE inspections SET second_sample_drawn = true, sample_size = 40 WHERE id = ?::uuid", good))
                .hasMessageContaining("cannot be changed");

        String bad = start();
        record(bad, 1, 13, 3).andExpect(status().isOk());
        submit(bad).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evaluatedResult").value("FAIL"))
                .andExpect(jsonPath("$.data.sampleSize").value(13));
    }

    @Test
    void anInconclusiveFirstSampleIsFollowedByASecondCountedTogether() throws Exception {
        String accepted = start();
        record(accepted, 1, 13, 1).andExpect(status().isOk());
        submit(accepted).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.secondSampleDrawn").value(true))
                .andExpect(jsonPath("$.data.sampleSize").value(26));
        // Both samples must be complete before submitting again.
        submit(accepted).andExpect(status().isUnprocessableContent());
        record(accepted, 14, 26, 2).andExpect(status().isOk());
        submit(accepted).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.evaluatedResult").value("PASS"));

        String rejected = start();
        record(rejected, 1, 13, 2).andExpect(status().isOk());
        submit(rejected).andExpect(status().isOk()).andExpect(jsonPath("$.data.secondSampleDrawn").value(true));
        record(rejected, 14, 26, 2).andExpect(status().isOk());
        submit(rejected).andExpect(status().isOk()).andExpect(jsonPath("$.data.evaluatedResult").value("FAIL"));
        org.assertj.core.api.Assertions.assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM audit_logs WHERE tenant_id = ?::uuid AND action = 'INSPECTION_SECOND_SAMPLE'",
                Long.class, owner.tenantId())).isEqualTo(2);
    }

    @Test
    void onlyALotAcceptedOnItsFirstSampleRaisesTheSwitchingScore() throws Exception {
        String first = start();
        record(first, 1, 13, 0).andExpect(status().isOk());
        submit(first).andExpect(status().isOk());
        approve(first).andExpect(status().isOk());
        state().andExpect(jsonPath("$.data[0].switchingScore").value(3));

        String second = start();
        record(second, 1, 13, 1).andExpect(status().isOk());
        submit(second).andExpect(status().isOk());
        record(second, 14, 26, 0).andExpect(status().isOk());
        submit(second).andExpect(status().isOk()).andExpect(jsonPath("$.data.evaluatedResult").value("PASS"));
        approve(second).andExpect(status().isOk());
        state().andExpect(jsonPath("$.data[0].switchingScore").value(0))
                .andExpect(jsonPath("$.data[0].recentResults").value("AA"));
    }
}
