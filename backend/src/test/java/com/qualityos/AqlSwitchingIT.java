package com.qualityos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The AQL switching rules over a series of lots from one supplier. AQL 4.0 at level II on lots of 100 is letter F:
 * normal 20 samples accepting 2, tightened 20 accepting 1, reduced 8 accepting 1 and rejecting from 3.
 */
class AqlSwitchingIT extends MasterDataFixture {

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
        // A template not tied to one material, so it fits the lots received.
        template = JsonPath.read(body(as(owner, postJson("/api/v1/inspection-templates",
                        Map.of("code", code(), "name", "Bar IQC", "inspectionType", "IQC")))
                .andExpect(status().isCreated())), "$.data.template.id");
        List<String> drafts = JsonPath.read(body(as(owner, get("/api/v1/inspection-templates/" + template))),
                "$.data.versions[?(@.status == 'DRAFT')].id");
        Map<String, Object> parameter = new java.util.HashMap<>(Map.of("code", "DIA", "name", "DIA",
                "dataType", "NUMERIC", "criticality", "MAJOR", "mandatory", true, "perSample", true,
                "evidenceRequired", false));
        parameter.put("minValue", "19.9");
        parameter.put("maxValue", "20.1");
        as(owner, put("/api/v1/inspection-templates/" + template + "/versions/" + drafts.getFirst())
                .contentType("application/json").content(json.writeValueAsString(Map.of("samplingType", "AQL",
                        "aql", "4.0", "inspectionLevel", "II", "acceptanceNumber", 0,
                        "parameters", List.of(parameter)))))
                .andExpect(status().isOk());
        as(owner, postJson("/api/v1/inspection-templates/" + template + "/publish",
                Map.of("effectiveFrom", java.time.LocalDate.now().toString()))).andExpect(status().isOk());
    }

    /** A lot of 100 from the supplier, started under the template. */
    private String start() throws Exception {
        String item = receivedLot();
        return idOf(body(as(owner, postJson("/api/v1/inspections", Map.of("receiptItemId", item, "templateId", template)))
                .andExpect(status().isCreated())));
    }

    /** A lot of 100 from the supplier, received into the one plant, awaiting inspection. */
    private String receivedLot() throws Exception {
        String order = body(as(owner, postJson("/api/v1/purchase-orders",
                        orderRequest(supplier, facility, material, "100", null)))
                .andExpect(status().isCreated()));
        String orderId = idOf(order);
        as(owner, postJson("/api/v1/purchase-orders/" + orderId + "/issue", Map.of())).andExpect(status().isOk());
        return JsonPath.read(body(receive(owner, new IssuedOrder(orderId, JsonPath.read(order, "$.data.items[0].id")),
                "100").andExpect(status().isCreated())), "$.data.items[0].id");
    }

    /** Inspects a lot with this many nonconforming samples, through to approval. */
    private String lot(int nonconforming) throws Exception {
        String inspection = submitted(nonconforming);
        as(manager, postJson("/api/v1/inspections/" + inspection + "/approve", Map.of())).andExpect(status().isOk());
        return inspection;
    }

    /** Inspects a lot with this many nonconforming samples, up to submission. */
    private String submitted(int nonconforming) throws Exception {
        String inspection = start();
        int samples = JsonPath.read(body(as(owner, get("/api/v1/inspections/" + inspection))), "$.data.sampleSize");
        List<Map<String, Object>> results = new ArrayList<>();
        for (int sample = 1; sample <= samples; sample++) {
            results.add(Map.of("parameterCode", "DIA", "sampleNumber", sample,
                    "valueNumeric", sample <= nonconforming ? "21" : "20"));
        }
        as(owner, postJson("/api/v1/inspections/" + inspection + "/execute", Map.of("results", results)))
                .andExpect(status().isOk());
        as(owner, postJson("/api/v1/inspections/" + inspection + "/submit", Map.of())).andExpect(status().isOk());
        return inspection;
    }

    private ResultActions state() throws Exception {
        return as(owner, get("/api/v1/inspection-templates/" + template + "/aql-switching"));
    }

    private ResultActions switchTo(Session who, String target, String reason) throws Exception {
        return as(who, postJson("/api/v1/inspection-templates/" + template + "/aql-switching",
                Map.of("supplierId", supplier, "state", target, "reason", reason)));
    }

    @Test
    void twoRejectionsInFiveLotsTightenAndFiveAcceptancesRelax() throws Exception {
        // One rejection, then four acceptances: the rejection leaves the window of five before the next.
        lot(3);
        for (int index = 0; index < 4; index++) {
            lot(0);
        }
        lot(3);
        state().andExpect(jsonPath("$.data[0].state").value("NORMAL"))
                .andExpect(jsonPath("$.data[0].supplierId").value(supplier))
                .andExpect(jsonPath("$.data[0].recentResults").value("AAAAR"));
        lot(2);
        lot(3);
        state().andExpect(jsonPath("$.data[0].state").value("TIGHTENED"));

        // Tightened: the same 20 samples, accepting one; two nonconforming now rejects.
        String tightened = lot(2);
        as(owner, get("/api/v1/inspections/" + tightened))
                .andExpect(jsonPath("$.data.specification.severity").value("TIGHTENED"))
                .andExpect(jsonPath("$.data.specification.codeLetter").value("F"))
                .andExpect(jsonPath("$.data.specification.acceptanceNumber").value(1))
                .andExpect(jsonPath("$.data.sampleSize").value(20))
                .andExpect(jsonPath("$.data.finalResult").value("FAIL"));
        for (int index = 0; index < 4; index++) {
            lot(1);
        }
        state().andExpect(jsonPath("$.data[0].state").value("TIGHTENED"))
                .andExpect(jsonPath("$.data[0].tightenedAcceptedInRow").value(4));
        lot(0);
        state().andExpect(jsonPath("$.data[0].state").value("NORMAL"));
        org.assertj.core.api.Assertions.assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM audit_logs WHERE tenant_id = ?::uuid AND action = 'AQL_SWITCHED'",
                Long.class, owner.tenantId())).isEqualTo(2);
    }

    @Test
    void lotsApprovedAtTheSameMomentAreAllCounted() throws Exception {
        List<String> lots = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            lots.add(submitted(0));
        }
        java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(lots.size());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(lots.size());
        try {
            List<java.util.concurrent.Future<Integer>> approvals = new ArrayList<>();
            for (String lot : lots) {
                approvals.add(pool.submit(() -> {
                    together.await();
                    return as(manager, postJson("/api/v1/inspections/" + lot + "/approve", Map.of()))
                            .andReturn().getResponse().getStatus();
                }));
            }
            for (var approval : approvals) {
                org.assertj.core.api.Assertions.assertThat(approval.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        // Each accepted lot adds 3; none is lost to another approving at the same time.
        state().andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].recentResults").value("AAAA"))
                .andExpect(jsonPath("$.data[0].switchingScore").value(12));
    }

    @Test
    void fiveRejectionsOnTightenedDiscontinueUntilResumed() throws Exception {
        lot(3);
        lot(3);
        for (int index = 0; index < 4; index++) {
            lot(2);
        }
        state().andExpect(jsonPath("$.data[0].tightenedRejected").value(4));
        lot(2);
        state().andExpect(jsonPath("$.data[0].state").value("DISCONTINUED"));

        String item = receivedLot();
        as(owner, postJson("/api/v1/inspections", Map.of("receiptItemId", item, "templateId", template)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("discontinued")));

        // Resumed only on tightened, by someone who manages templates, with a reason.
        switchTo(manager, "NORMAL", "Supplier fixed the furnace").andExpect(status().isUnprocessableContent());
        switchTo(addMember(owner, "VIEWER"), "TIGHTENED", "Supplier fixed the furnace").andExpect(status().isForbidden());
        switchTo(owner, "TIGHTENED", "Supplier fixed the furnace").andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].state").value("TIGHTENED"));
        String resumed = idOf(body(as(owner, postJson("/api/v1/inspections",
                        Map.of("receiptItemId", item, "templateId", template)))
                .andExpect(status().isCreated())));
        as(owner, get("/api/v1/inspections/" + resumed))
                .andExpect(jsonPath("$.data.specification.severity").value("TIGHTENED"));
    }

    @Test
    void aHighScoreLetsSomeoneReduceAndAGapReturnsToNormal() throws Exception {
        lot(0);
        // Each lot accepted with no more than the next tighter AQL accepts (2.5: one) adds 3.
        state().andExpect(jsonPath("$.data[0].switchingScore").value(3))
                .andExpect(jsonPath("$.data[0].mayReduce").value(false));
        switchTo(owner, "REDUCED", "Steady production").andExpect(status().isUnprocessableContent());
        lot(2);
        state().andExpect(jsonPath("$.data[0].switchingScore").value(0));

        asDatabaseSuperuser().update("UPDATE aql_switching_states SET switching_score = 27 WHERE template_id = ?::uuid",
                template);
        lot(1);
        state().andExpect(jsonPath("$.data[0].switchingScore").value(30))
                .andExpect(jsonPath("$.data[0].mayReduce").value(true));
        switchTo(owner, "REDUCED", "Steady production for three months").andExpect(status().isOk());

        // Reduced: 8 samples accepting 1, rejecting from 3. Two is accepted, and inspection returns to normal.
        String reduced = lot(2);
        as(owner, get("/api/v1/inspections/" + reduced))
                .andExpect(jsonPath("$.data.sampleSize").value(8))
                .andExpect(jsonPath("$.data.specification.rejectionNumber").value(3))
                .andExpect(jsonPath("$.data.finalResult").value("PASS"));
        state().andExpect(jsonPath("$.data[0].state").value("NORMAL"))
                .andExpect(jsonPath("$.data[0].switchingScore").value(0));

        // Another organisation sees nothing of it.
        Session stranger = register();
        as(stranger, get("/api/v1/inspection-templates/" + template + "/aql-switching")).andExpect(status().isNotFound());
    }
}
