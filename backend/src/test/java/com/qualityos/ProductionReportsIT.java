package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Production in the analytics overview, and work orders, shop-floor losses and downtime as reports. */
class ProductionReportsIT extends TraceabilityFixture {

    private static final String API = "/api/v1/manufacturing";

    private Session supervisor;
    private Session operator;
    private String facility;
    private String product;
    private String machine;
    private String machineCode;

    @BeforeEach
    void plant() throws Exception {
        supervisor = addMember(owner, "PRODUCTION_MANAGER");
        operator = addMember(owner, "PRODUCTION_OPERATOR");
        facility = facility(owner);
        product = idOf(body(as(owner, postJson("/api/v1/products", Map.of("code", code(), "name", "Shaft",
                        "productType", "FINISHED", "uomId", unitId(owner, "PCS"))))
                .andExpect(status().isCreated())));
        as(owner, put(API + "/settings").contentType("application/json").content(
                "{\"manufacturingModel\": \"DISCRETE\", \"separateApprover\": true, \"workOrderRequiresBom\": false}"))
                .andExpect(status().isOk());
        String centre = idOf(body(send(owner, "/work-centers", Map.of("code", "WC-" + code(), "name", "Turning",
                "facilityId", facility)).andExpect(status().isCreated())));
        machineCode = "M-" + code();
        machine = idOf(body(send(owner, "/machines", Map.of("code", machineCode, "name", "Lathe",
                "workCenterId", centre)).andExpect(status().isCreated())));
        String routing = JsonPath.read(body(send(owner, "/routings", Map.of("productId", product))
                .andExpect(status().isCreated())), "$.data.revision.id");
        send(owner, "/routings/" + routing + "/operations", Map.of("sequence", 10, "name", "Turn",
                "workCenterId", centre, "setupMinutes", "0", "runMinutes", "60")).andExpect(status().isOk());
        send(owner, "/routings/" + routing + "/submit", Map.of()).andExpect(status().isOk());
        send(manager, "/routings/" + routing + "/approve", Map.of()).andExpect(status().isOk());
    }

    private ResultActions send(Session session, String path, Object body) throws Exception {
        return as(session, postJson(API + path, body));
    }

    private List<List<String>> rows(String slug) throws Exception {
        return JsonPath.read(body(as(owner, get("/api/v1/reports/" + slug)).andExpect(status().isOk())),
                "$.data.rows");
    }

    @Test
    void productionShowsInTheOverviewAndInReports() throws Exception {
        String order = JsonPath.read(body(send(owner, "/work-orders", Map.of("productId", product,
                "facilityId", facility, "quantity", "10", "plannedStart", LocalDate.now().toString(),
                "plannedEnd", LocalDate.now().plusDays(3).toString())).andExpect(status().isCreated())),
                "$.data.workOrder.id");
        send(owner, "/work-orders/" + order + "/submit", Map.of()).andExpect(status().isOk());
        for (String step : List.of("approve", "release", "start")) {
            send(supervisor, "/work-orders/" + order + "/" + step, Map.of()).andExpect(status().isOk());
        }
        String number = JsonPath.read(body(as(owner, get(API + "/work-orders/" + order))),
                "$.data.workOrder.workOrderNumber");
        String operation = "/operations/" + JsonPath.<String>read(body(as(owner, get(API + "/work-orders/" + order))),
                "$.data.operations[0].id");
        send(operator, operation + "/start", Map.of()).andExpect(status().isOk());
        send(operator, operation + "/reports", Map.of("quantity", "10", "goodQuantity", "8",
                "rejectedQuantity", "1", "scrapQuantity", "1", "reworkQuantity", "0")).andExpect(status().isOk());
        send(owner, "/machines/" + machine + "/status", Map.of("status", "BREAKDOWN", "reason", "Spindle seized"))
                .andExpect(status().isOk());

        String overview = body(as(owner, get("/api/v1/analytics/overview")).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(overview, "$.data.production.openWorkOrders")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(overview, "$.data.production.lateWorkOrders")).isZero();
        assertThat(JsonPath.<Double>read(overview, "$.data.production.lossRate")).isEqualTo(20.0);
        assertThat(JsonPath.<Object>read(overview, "$.data.production.onTimeRate")).isNull();

        List<List<String>> orders = rows("work-orders");
        assertThat(orders).hasSize(1);
        assertThat(orders.getFirst()).startsWith(number, "Shaft").contains("IN_PROGRESS", "10");
        List<List<String>> losses = rows("production-quality");
        assertThat(losses).hasSize(1);
        assertThat(losses.getFirst()).startsWith("Shaft", "10 Turn").endsWith("10", "8", "1", "1", "0", "20.0");
        List<List<String>> stops = rows("downtime");
        assertThat(stops).hasSize(1);
        assertThat(stops.getFirst()).contains("Open", machineCode, "MACHINE_BREAKDOWN", "No", "Spindle seized");

        for (String slug : List.of("work-orders", "production-quality", "downtime")) {
            as(owner, get("/api/v1/reports/" + slug + ".pdf")).andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/pdf"));
            as(owner, get("/api/v1/reports/" + slug + ".xlsx")).andExpect(status().isOk());
        }
        as(operator, get("/api/v1/reports/work-orders")).andExpect(status().isForbidden());
    }
}
