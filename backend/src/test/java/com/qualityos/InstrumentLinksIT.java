package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Gauges kept at machines, measurements that name their instrument, and failed calibrations that reach the floor. */
class InstrumentLinksIT extends TraceabilityFixture {

    private static final String API = "/api/v1/manufacturing";

    private Session supervisor;
    private Session operator;
    private String facility;
    private String product;
    private String machine;

    @BeforeEach
    void plant() throws Exception {
        finalInspectionTemplate();
        supervisor = addMember(owner, "PRODUCTION_MANAGER");
        operator = addMember(owner, "PRODUCTION_OPERATOR");
        facility = facility(owner);
        product = idOf(body(as(owner, postJson("/api/v1/products", Map.of("code", code(), "name", "Shaft",
                        "productType", "FINISHED", "uomId", unitId(owner, "PCS"))))
                .andExpect(status().isCreated())));
        as(owner, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(API + "/settings")
                .contentType("application/json")
                .content("{\"manufacturingModel\": \"DISCRETE\", \"separateApprover\": true, \"workOrderRequiresBom\": false}"))
                .andExpect(status().isOk());
        String centre = idOf(body(send(owner, "/work-centers", Map.of("code", "WC-" + code(), "name", "Turning",
                "facilityId", facility)).andExpect(status().isCreated())));
        machine = idOf(body(send(owner, "/machines", Map.of("code", "M-" + code(), "name", "Lathe",
                "workCenterId", centre)).andExpect(status().isCreated())));
        String routing = JsonPath.read(body(send(owner, "/routings", Map.of("productId", product))
                .andExpect(status().isCreated())), "$.data.revision.id");
        String withOperation = body(send(owner, "/routings/" + routing + "/operations", Map.of("sequence", 10,
                "name", "Turn", "workCenterId", centre, "setupMinutes", "0", "runMinutes", "60"))
                .andExpect(status().isOk()));
        String operation = JsonPath.read(withOperation, "$.data.operations[0].id");
        send(owner, "/routings/" + routing + "/operations/" + operation + "/parameters", Map.of("code", "DIA",
                "name", "Diameter", "minimum", "19.9", "maximum", "20.1")).andExpect(status().isOk());
        send(owner, "/routings/" + routing + "/submit", Map.of()).andExpect(status().isOk());
        send(manager, "/routings/" + routing + "/approve", Map.of()).andExpect(status().isOk());
    }

    private ResultActions send(Session session, String path, Object body) throws Exception {
        return as(session, postJson(API + path, body));
    }

    private String instrument(String code, String facilityId, LocalDate nextDueOn) throws Exception {
        return idOf(body(as(owner, postJson("/api/v1/instruments", Map.of("instrumentCode", code,
                "name", "Micrometer " + code, "category", "Dimensional", "facilityId", facilityId,
                "frequencyDays", 180, "nextDueOn", nextDueOn.toString()))).andExpect(status().isCreated())));
    }

    /** The first operation of a started order, started by the operator. */
    private String runningOperation() throws Exception {
        String order = JsonPath.read(body(send(owner, "/work-orders", Map.of("productId", product,
                "facilityId", facility, "quantity", "10", "plannedStart", LocalDate.now().toString(),
                "plannedEnd", LocalDate.now().plusDays(3).toString())).andExpect(status().isCreated())),
                "$.data.workOrder.id");
        send(owner, "/work-orders/" + order + "/submit", Map.of()).andExpect(status().isOk());
        for (String step : List.of("approve", "release", "start")) {
            send(supervisor, "/work-orders/" + order + "/" + step, Map.of()).andExpect(status().isOk());
        }
        String operation = "/operations/" + JsonPath.<String>read(body(as(owner, get(API + "/work-orders/" + order))),
                "$.data.operations[0].id");
        send(operator, operation + "/start", Map.of()).andExpect(status().isOk());
        return operation;
    }

    @Test
    void aFailedCalibrationReachesTheOperationsItMeasured() throws Exception {
        String micrometer = instrument("MIC-1", facility, LocalDate.now().plusDays(90));
        String spare = instrument("MIC-2", facility, LocalDate.now().plusDays(90));
        String overdue = instrument("MIC-3", facility, LocalDate.now().minusDays(1));
        String elsewhere = instrument("MIC-4", facility(owner), LocalDate.now().plusDays(90));

        // A machine keeps instruments of its own facility only.
        send(owner, "/machines/" + machine + "/instruments", Map.of("instrumentId", elsewhere))
                .andExpect(status().isUnprocessableContent());
        send(operator, "/machines/" + machine + "/instruments", Map.of("instrumentId", micrometer))
                .andExpect(status().isForbidden());
        send(owner, "/machines/" + machine + "/instruments", Map.of("instrumentId", micrometer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].instrumentCode").value(org.hamcrest.Matchers.contains("MIC-1")));
        send(owner, "/machines/" + machine + "/instruments", Map.of("instrumentId", spare)).andExpect(status().isOk());
        as(owner, delete(API + "/machines/" + machine + "/instruments/" + spare))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].instrumentCode").value(org.hamcrest.Matchers.contains("MIC-1")));

        // The machine's gauge is offered first; an overdue one is not offered and cannot be used.
        String operation = runningOperation();
        String detail = body(as(operator, get(API + operation)).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(detail, "$.data.instruments[*].instrumentCode"))
                .containsExactly("MIC-1", "MIC-2");
        send(operator, operation + "/measurements", Map.of("parameterCode", "DIA", "value", "20.0",
                "instrumentId", overdue))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("overdue")));
        send(operator, operation + "/measurements", Map.of("parameterCode", "DIA", "value", "20.0",
                "instrumentId", micrometer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.measurements[0].instrumentCode").value("MIC-1"));
        String label = JsonPath.<String>read(detail, "$.data.operation.workOrderNumber") + " operation 10";

        // Failing calibration puts the operation in doubt and names it in the NCR.
        Session qualityManager = addMember(owner, "QUALITY_MANAGER");
        String failed = body(as(owner, postJson("/api/v1/instruments/" + micrometer + "/calibrations", Map.of(
                "calibratedOn", LocalDate.now().toString(), "result", "FAIL", "performedBy", "Lab")))
                .andExpect(status().isCreated()));
        assertThat(failed).isNotEmpty();
        assertThat(emailedSubjects(qualityManager)).anyMatch(subject -> subject.endsWith(" failed calibration"));
        String reviews = body(as(owner, get("/api/v1/instruments/" + micrometer + "/impact-reviews")));
        assertThat(JsonPath.<List<String>>read(reviews, "$.data[0].otherUses[*].label")).containsExactly(label);
        assertThat(JsonPath.<List<Object>>read(reviews, "$.data[0].reviews")).isEmpty();
        String ncr = JsonPath.read(reviews, "$.data[0].ncrId");
        assertThat(JsonPath.<String>read(body(as(owner, get("/api/v1/ncrs/" + ncr))), "$.data.description"))
                .contains(label);
        String usage = body(as(owner, get("/api/v1/instruments/" + micrometer + "/usage")));
        assertThat(JsonPath.<List<String>>read(usage, "$.data[*].label")).containsExactly(label);
        assertThat(JsonPath.<List<Boolean>>read(usage, "$.data[*].suspect")).containsExactly(true);
        assertThat(JsonPath.<String>read(usage, "$.data[0].link")).startsWith("/manufacturing/shop-floor/");
    }
}
