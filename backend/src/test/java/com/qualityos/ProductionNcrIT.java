package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Goods rejected or scrapped on the shop floor raise an NCR on the work order's batch, with a defect category. */
class ProductionNcrIT extends TraceabilityFixture {

    private static final String API = "/api/v1/manufacturing";

    private Session supervisor;
    private Session operator;
    private String facility;
    private String product;
    private String category;

    @BeforeEach
    void plant() throws Exception {
        finalInspectionTemplate();
        supervisor = addMember(owner, "PRODUCTION_MANAGER");
        operator = addMember(owner, "PRODUCTION_OPERATOR");
        facility = facility(owner);
        String material = material(owner, false, "KG");
        product = idOf(body(as(owner, postJson("/api/v1/products", Map.of("code", code(), "name", "Shaft",
                        "productType", "FINISHED", "uomId", unitId(owner, "PCS"))))
                .andExpect(status().isCreated())));
        String bom = JsonPath.read(body(send(owner, "/boms", Map.of("productId", product))
                .andExpect(status().isCreated())), "$.data.revision.id");
        send(owner, "/boms/" + bom + "/lines", Map.of("materialId", material, "quantity", "1",
                "uomId", unitId(owner, "KG"))).andExpect(status().isOk());
        String centre = idOf(body(send(owner, "/work-centers", Map.of("code", "WC-" + code(), "name", "Turning",
                "facilityId", facility)).andExpect(status().isCreated())));
        String routing = JsonPath.read(body(send(owner, "/routings", Map.of("productId", product))
                .andExpect(status().isCreated())), "$.data.revision.id");
        send(owner, "/routings/" + routing + "/operations", Map.of("sequence", 10, "name", "Turn",
                "workCenterId", centre, "setupMinutes", "0", "runMinutes", "60")).andExpect(status().isOk());
        for (String path : List.of("/boms/" + bom, "/routings/" + routing)) {
            send(owner, path + "/submit", Map.of()).andExpect(status().isOk());
            send(manager, path + "/approve", Map.of()).andExpect(status().isOk());
        }
        category = idOf(body(as(owner, postJson("/api/v1/defect-categories",
                Map.of("code", "DIM", "name", "Dimensional"))).andExpect(status().isCreated())));
    }

    private ResultActions send(Session session, String path, Object body) throws Exception {
        return as(session, postJson(API + path, body));
    }

    /** An order of 20 in progress, with its first operation started by the operator. */
    private String running() throws Exception {
        String order = JsonPath.read(body(send(owner, "/work-orders", Map.of("productId", product,
                "facilityId", facility, "quantity", "20", "plannedStart", LocalDate.now().toString(),
                "plannedEnd", LocalDate.now().plusDays(3).toString())).andExpect(status().isCreated())),
                "$.data.workOrder.id");
        send(owner, "/work-orders/" + order + "/submit", Map.of()).andExpect(status().isOk());
        for (String step : List.of("approve", "release", "start")) {
            send(supervisor, "/work-orders/" + order + "/" + step, Map.of()).andExpect(status().isOk());
        }
        String operation = operation(order);
        send(operator, operation + "/start", Map.of()).andExpect(status().isOk());
        return order;
    }

    private String operation(String order) throws Exception {
        return "/operations/" + JsonPath.<String>read(body(as(owner, get(API + "/work-orders/" + order))),
                "$.data.operations[0].id");
    }

    private Map<String, Object> report(String quantity, String good, String rejected, String scrap, String rework) {
        return new java.util.HashMap<>(Map.of("quantity", quantity, "goodQuantity", good,
                "rejectedQuantity", rejected, "scrapQuantity", scrap, "reworkQuantity", rework));
    }

    private List<String> ncrsFor(String order) throws Exception {
        String number = JsonPath.read(body(as(owner, get(API + "/work-orders/" + order))),
                "$.data.workOrder.workOrderNumber");
        return JsonPath.read(body(as(owner, get("/api/v1/ncrs?size=100&q=" + number))), "$.data.items[*].id");
    }

    @Test
    void rejectsAndScrapRaiseOneNcrPerReport() throws Exception {
        String order = running();
        String operation = operation(order);
        as(operator, get(API + "/operations/defect-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("DIM"));

        Map<String, Object> bad = report("10", "7", "2", "1", "1");
        bad.put("defectCategoryId", UUID.randomUUID().toString());
        send(operator, operation + "/reports", bad)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("This defect category does not exist."));

        send(operator, operation + "/reports", report("5", "5", "0", "0", "0")).andExpect(status().isOk());
        assertThat(ncrsFor(order)).isEmpty();

        Map<String, Object> rejected = report("10", "7", "2", "1", "1");
        rejected.put("defectCategoryId", category);
        rejected.put("comment", "Oversize diameter");
        for (int i = 0; i < 2; i++) {
            as(operator, postJson(API + operation + "/reports", rejected).header("Idempotency-Key", "reject-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reports[1].defectCategoryId").value(category));
        }
        List<String> raised = ncrsFor(order);
        assertThat(raised).hasSize(1);
        String ncr = body(as(manager, get("/api/v1/ncrs/" + raised.getFirst())).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(ncr, "$.data.source")).isEqualTo("PRODUCTION");
        assertThat(JsonPath.<String>read(ncr, "$.data.workOrderId")).isEqualTo(order);
        assertThat(JsonPath.<String>read(ncr, "$.data.defectCategoryName")).isEqualTo("Dimensional");
        assertThat(new java.math.BigDecimal(JsonPath.<String>read(ncr, "$.data.rejectedQuantity")))
                .isEqualByComparingTo("3");
        assertThat(JsonPath.<String>read(ncr, "$.data.severity")).isEqualTo("MINOR");
        assertThat(JsonPath.<String>read(ncr, "$.data.title")).endsWith("operation 10: 2 rejected, 1 scrapped");
        assertThat(JsonPath.<String>read(ncr, "$.data.description"))
                .contains("1 of the rejected go to rework").contains("Oversize diameter");
        assertThat(JsonPath.<String>read(ncr, "$.data.batchNumber")).startsWith("PB-");

        // The work order's quality view lists it with the batch's other NCRs.
        as(manager, get(API + "/work-orders/" + order + "/quality"))
                .andExpect(jsonPath("$.data.ncrs[0].id").value(raised.getFirst()));
    }

    @Test
    void anOrganisationCanKeepRejectsOutOfItsNcrs() throws Exception {
        as(owner, put(API + "/settings").contentType("application/json").content("""
                {"manufacturingModel": "DISCRETE", "separateApprover": true, "rejectionRaisesNcr": false}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rejectionRaisesNcr").value(false));
        String order = running();
        send(operator, operation(order) + "/reports", report("4", "2", "1", "1", "0"))
                .andExpect(status().isOk());
        assertThat(ncrsFor(order)).isEmpty();
        assertThat(body(as(owner, get(API + "/work-orders/" + order + "/quality")))).doesNotContain("\"PRODUCTION\"");
        as(owner, get(API + "/settings")).andExpect(jsonPath("$.data.rejectionRaisesNcr").value(false))
                .andExpect(jsonPath("$.data.manufacturingModel").value(containsString("DISCRETE")));
    }
}
