package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

class ShopFloorIT extends TraceabilityFixture {

    private static final String API = "/api/v1/manufacturing";

    private Session supervisor;
    private Session operator;
    private String facility;
    private String product;
    private String centre;
    private String machine;

    /**
     * A gearbox is machined, with the spindle held between 800 and 1200 rpm, then inspected. Both operations are at one
     * work centre with one machine.
     */
    @BeforeEach
    void gearbox() throws Exception {
        supervisor = addMember(owner, "PRODUCTION_MANAGER");
        operator = addMember(owner, "PRODUCTION_OPERATOR");
        facility = facility(owner);
        String material = material(owner, false, "KG");
        product = idOf(body(as(owner, postJson("/api/v1/products", Map.of("code", code(), "name", "Gearbox",
                        "productType", "FINISHED", "uomId", unitId(owner, "PCS"))))
                .andExpect(status().isCreated())));
        String bom = JsonPath.read(body(send(owner, "/boms", Map.of("productId", product))
                .andExpect(status().isCreated())), "$.data.revision.id");
        send(owner, "/boms/" + bom + "/lines", Map.of("materialId", material, "quantity", "2",
                "uomId", unitId(owner, "KG"))).andExpect(status().isOk());
        approve("/boms", bom);

        centre = idOf(body(send(owner, "/work-centers", Map.of("code", "WC-" + code(), "name", "Machining",
                "facilityId", facility)).andExpect(status().isCreated())));
        machine = idOf(body(send(owner, "/machines", Map.of("code", "M-" + code(), "name", "Haas VF-2",
                "workCenterId", centre)).andExpect(status().isCreated())));
        String routing = JsonPath.read(body(send(owner, "/routings", Map.of("productId", product))
                .andExpect(status().isCreated())), "$.data.revision.id");
        String machining = JsonPath.read(body(send(owner, "/routings/" + routing + "/operations", Map.of(
                "sequence", 10, "name", "Machine", "workCenterId", centre, "setupMinutes", "15",
                "runMinutes", "2", "instructions", "Clamp on the left datum.")).andExpect(status().isOk())),
                "$.data.operations[0].id");
        send(owner, "/routings/" + routing + "/operations/" + machining + "/parameters",
                Map.of("code", "RPM", "name", "Spindle speed", "minimum", "800", "maximum", "1200"))
                .andExpect(status().isOk());
        send(owner, "/routings/" + routing + "/operations", Map.of("sequence", 20, "name", "Inspect",
                "workCenterId", centre, "setupMinutes", "0", "runMinutes", "1")).andExpect(status().isOk());
        approve("/routings", routing);
    }

    private void approve(String path, String id) throws Exception {
        send(owner, path + "/" + id + "/submit", Map.of()).andExpect(status().isOk());
        send(manager, path + "/" + id + "/approve", Map.of()).andExpect(status().isOk());
    }

    private ResultActions send(Session session, String path, Object body) throws Exception {
        return as(session, postJson(API + path, body));
    }

    private ResultActions sendWithKey(Session session, String path, Object body, String key) throws Exception {
        return as(session, postJson(API + path, body).header("Idempotency-Key", key));
    }

    private String order(String quantity) throws Exception {
        String order = JsonPath.read(body(send(owner, "/work-orders", Map.of("productId", product,
                        "facilityId", facility, "quantity", quantity, "plannedStart", TODAY,
                        "plannedEnd", LocalDate.now().plusDays(2).toString())).andExpect(status().isCreated())),
                "$.data.workOrder.id");
        send(owner, "/work-orders/" + order + "/submit", Map.of()).andExpect(status().isOk());
        send(supervisor, "/work-orders/" + order + "/approve", Map.of()).andExpect(status().isOk());
        send(supervisor, "/work-orders/" + order + "/release", Map.of()).andExpect(status().isOk());
        return order;
    }

    private String workOrder(String order) throws Exception {
        return body(as(owner, get(API + "/work-orders/" + order)).andExpect(status().isOk()));
    }

    private String operation(String order, int index) throws Exception {
        return JsonPath.read(workOrder(order), "$.data.operations[" + index + "].id");
    }

    private String detail(String operation) throws Exception {
        return body(as(owner, get(API + "/operations/" + operation)).andExpect(status().isOk()));
    }

    private String machineStatus() {
        return asDatabaseSuperuser().queryForObject("SELECT status FROM machines WHERE id = ?::uuid", String.class,
                machine);
    }

    private Map<String, Object> report(String quantity, String good, String rejected, String scrap) {
        return new HashMap<>(Map.of("quantity", quantity, "goodQuantity", good, "rejectedQuantity", rejected,
                "scrapQuantity", scrap));
    }

    private void settings(Map<String, Object> rules) throws Exception {
        Map<String, Object> request = new HashMap<>(Map.of("manufacturingModel", "DISCRETE", "separateApprover", true));
        request.putAll(rules);
        as(owner, put(API + "/settings").contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void operationsRunInTurnAndEveryQuantityReconciles() throws Exception {
        String order = order("10");
        String first = operation(order, 0);
        String second = operation(order, 1);
        String path = "/operations/" + first;

        // Released, the operations wait for production to start; they carry their parameters as approved.
        String view = detail(first);
        assertThat(JsonPath.<String>read(view, "$.data.operation.status")).isEqualTo("PENDING");
        assertThat(JsonPath.<String>read(view, "$.data.parameters[0].code")).isEqualTo("RPM");
        assertThat(JsonPath.<String>read(view, "$.data.instructions")).isEqualTo("Clamp on the left datum.");
        send(operator, path + "/start", Map.of()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("is released")));

        send(supervisor, "/work-orders/" + order + "/start", Map.of()).andExpect(status().isOk());
        assertThat(JsonPath.<List<String>>read(workOrder(order), "$.data.operations[*].status"))
                .containsExactly("READY", "PENDING");

        // Starting takes the operation and the machine.
        send(operator, path + "/start", Map.of("machineId", machine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.operation.operatorId").value(operator.userId().toString()))
                .andExpect(jsonPath("$.data.operation.input").value("10.000000"));
        assertThat(machineStatus()).isEqualTo("RUNNING");
        Session colleague = addMember(owner, "PRODUCTION_OPERATOR");
        send(colleague, path + "/reports", report("1", "1", "0", "0"))
                .andExpect(status().isForbidden());

        // Good, rejected and scrap must add up, in whole pieces, and never past what came in.
        send(operator, path + "/reports", report("6", "5", "0", "0"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("Good 5, rejected 0 and scrap 0 make 5, not the 6 reported."));
        send(operator, path + "/reports", report("1.5", "1.5", "0", "0"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("Pieces are counted in whole numbers."));
        Map<String, Object> firstReport = report("6", "5", "1", "0");
        firstReport.put("reworkQuantity", "2");
        send(operator, path + "/reports", firstReport).andExpect(status().isUnprocessableContent());
        firstReport.put("reworkQuantity", "1");
        sendWithKey(operator, path + "/reports", firstReport, "shift-1").andExpect(status().isOk());
        sendWithKey(operator, path + "/reports", firstReport, "shift-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation.goodQuantity").value("5.000000"))
                .andExpect(jsonPath("$.data.operation.waiting").value("4.000000"));
        send(operator, path + "/reports", report("5", "5", "0", "0"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("Operation 10 can take 4 more: the order is for 10."));

        // The next operation waits its turn.
        send(operator, "/operations/" + second + "/start", Map.of()).andExpect(status().isUnprocessableContent());

        // Measurements are kept, and flagged when outside the limits.
        send(operator, path + "/measurements", Map.of("parameterCode", "RPM", "value", "1300"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.measurements[0].withinLimits").value(false));
        send(operator, path + "/measurements", Map.of("parameterCode", "rpm", "value", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.measurements[1].withinLimits").value(true));
        send(operator, path + "/measurements", Map.of("parameterCode", "TEMP", "value", "1"))
                .andExpect(status().isUnprocessableContent());

        // A breakdown stops the operation and the machine; the machine must be fixed before it runs again.
        send(operator, path + "/pause", Map.of("category", "MACHINE_BREAKDOWN", "reason", "Spindle seized"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation.status").value("PAUSED"))
                .andExpect(jsonPath("$.data.operation.statusReason").value("Machine breakdown: Spindle seized"));
        assertThat(machineStatus()).isEqualTo("BREAKDOWN");
        send(operator, path + "/resume", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("is broken down")));
        send(owner, "/machines/" + machine + "/status", Map.of("status", "AVAILABLE")).andExpect(status().isOk());
        send(operator, path + "/resume", Map.of()).andExpect(status().isOk());
        send(operator, path + "/reports", report("4", "4", "0", "0")).andExpect(status().isOk());

        send(operator, path + "/complete", Map.of("setupMinutes", "9999"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("Setting up cannot have taken longer")));
        send(operator, path + "/complete", Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation.status").value("COMPLETED"));
        assertThat(machineStatus()).isEqualTo("AVAILABLE");

        // What came out good is what the next operation can take; less going through needs a reason.
        String next = "/operations/" + second;
        send(operator, next + "/start", Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation.input").value("9.000000"));
        send(operator, next + "/reports", report("10", "10", "0", "0"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("can take 9 more")));
        send(operator, next + "/reports", report("8", "8", "0", "0")).andExpect(status().isOk());
        send(operator, next + "/complete", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("Only 8 of 9 went through operation 20. Say why it is complete."));
        send(operator, next + "/complete", Map.of("reason", "One left for the next shift"))
                .andExpect(status().isOk());

        view = workOrder(order);
        assertThat(JsonPath.<List<String>>read(view, "$.data.operations[*].goodQuantity"))
                .containsExactly("9.000000", "8.000000");
        assertThat(JsonPath.<List<String>>read(view, "$.data.operations[*].rejectedQuantity"))
                .containsExactly("1.000000", "0.000000");
        assertThat(JsonPath.<List<String>>read(detail(first), "$.data.events[*].eventType"))
                .containsExactly("READY", "STARTED", "PAUSED", "RESUMED", "COMPLETED");
        assertThat(JsonPath.<String>read(detail(first), "$.data.events[2].lossCategory"))
                .isEqualTo("MACHINE_BREAKDOWN");
        assertThat(JsonPath.<List<String>>read(detail(first), "$.data.reports[*].goodQuantity"))
                .containsExactly("5.000000", "4.000000");

        // Reports, measurements and the timeline are history; a completed operation does not change.
        for (String table : List.of("operation_reports", "operation_measurements", "operation_events")) {
            assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                    "DELETE FROM " + table + " WHERE operation_id = ?::uuid", first)))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                "UPDATE work_order_operations SET good_quantity = 10 WHERE id = ?::uuid", first)))
                .isInstanceOf(DataAccessException.class);
        assertThat(asDatabaseSuperuser().queryForList(
                "SELECT action FROM audit_logs WHERE entity_id = ?", String.class, first))
                .contains("OPERATION_STARTED", "OPERATION_REPORTED", "OPERATION_MEASURED", "OPERATION_COMPLETED");
    }

    @Test
    void supervisorsAssignBlockSkipAndReverseAndAHoldPausesTheFloor() throws Exception {
        settings(Map.of("operationsInSequence", false, "operationRequiresMachine", true));
        String order = order("10");
        send(supervisor, "/work-orders/" + order + "/start", Map.of()).andExpect(status().isOk());
        assertThat(JsonPath.<List<String>>read(workOrder(order), "$.data.operations[*].status"))
                .containsExactly("READY", "READY");
        String first = "/operations/" + operation(order, 0);
        String second = "/operations/" + operation(order, 1);

        // The organisation wants a machine named, and a supervisor gives the work out.
        send(operator, first + "/start", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("Choose the machine operation 10 runs on."));
        Session colleague = addMember(owner, "PRODUCTION_OPERATOR");
        send(operator, first + "/assign", Map.of("operatorId", colleague.userId().toString()))
                .andExpect(status().isForbidden());
        Session viewer = addMember(owner, "VIEWER");
        send(supervisor, first + "/assign", Map.of("operatorId", viewer.userId().toString()))
                .andExpect(status().isUnprocessableContent());
        send(supervisor, first + "/assign", Map.of("operatorId", colleague.userId().toString(),
                "machineId", machine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation.machineId").value(machine));
        send(operator, first + "/start", Map.of()).andExpect(status().isForbidden());
        send(colleague, first + "/start", Map.of()).andExpect(status().isOk());

        // One machine runs one operation at a time.
        String other = order("5");
        send(supervisor, "/work-orders/" + other + "/start", Map.of()).andExpect(status().isOk());
        send(operator, "/operations/" + operation(other, 0) + "/start", Map.of("machineId", machine))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("running another operation")));

        // Out of order, an operation still takes only what has come through good before it.
        send(supervisor, second + "/assign", Map.of("machineId", machine)).andExpect(status().isOk());
        send(supervisor, first + "/reports", report("3", "3", "0", "0")).andExpect(status().isOk());

        // A mistaken report is reversed, once, by a supervisor.
        String reportId = JsonPath.read(detail(operation(order, 0)), "$.data.reports[0].id");
        send(colleague, first + "/reports/" + reportId + "/reverse", Map.of("reason", "Miscounted"))
                .andExpect(status().isForbidden());
        send(supervisor, first + "/reports/" + reportId + "/reverse", Map.of("reason", "Miscounted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation.goodQuantity").value("0.000000"))
                .andExpect(jsonPath("$.data.reports[0].reversed").value(true))
                .andExpect(jsonPath("$.data.reports[1].goodQuantity").value("-3.000000"));
        send(supervisor, first + "/reports/" + reportId + "/reverse", Map.of("reason", "Again"))
                .andExpect(status().isUnprocessableContent());
        send(colleague, first + "/reports", report("4", "4", "0", "0")).andExpect(status().isOk());

        // Holding the order pauses what runs and frees the machine; nothing runs until it resumes.
        send(supervisor, "/work-orders/" + order + "/hold", Map.of("reason", "Customer change"))
                .andExpect(status().isOk());
        String held = detail(operation(order, 0));
        assertThat(JsonPath.<String>read(held, "$.data.operation.status")).isEqualTo("PAUSED");
        assertThat(JsonPath.<String>read(held, "$.data.events[-1].lossCategory")).isEqualTo("ORDER_ON_HOLD");
        assertThat(machineStatus()).isEqualTo("AVAILABLE");
        send(colleague, first + "/resume", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("is on hold")));
        send(supervisor, "/work-orders/" + order + "/resume", Map.of()).andExpect(status().isOk());

        // The second operation can take the 4 good so far, on the machine the first one freed.
        send(supervisor, second + "/start", Map.of()).andExpect(status().isOk());
        send(supervisor, second + "/reports", report("5", "5", "0", "0"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("can take 4 more")));
        send(supervisor, second + "/reports", report("2", "2", "0", "0")).andExpect(status().isOk());
        send(supervisor, first + "/reports/" + JsonPath.read(detail(operation(order, 0)), "$.data.reports[2].id")
                + "/reverse", Map.of("reason", "Wrong order"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("has already processed 2")));
        send(colleague, first + "/complete", Map.of())
                .andExpect(status().isUnprocessableContent());
        send(supervisor, second + "/complete", Map.of("reason", "Rest goes to rework"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("Operation 10 is not complete yet, so operation 20 cannot be."));

        // Blocking and skipping are for supervisors; skipping is only for work nobody reported on.
        String otherFirst = "/operations/" + operation(other, 0);
        send(operator, otherFirst + "/block", Map.of("category", "MATERIAL_SHORTAGE", "reason", "No bar"))
                .andExpect(status().isForbidden());
        send(supervisor, otherFirst + "/block", Map.of("category", "MATERIAL_SHORTAGE", "reason", "No bar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation.status").value("BLOCKED"));
        send(supervisor, otherFirst + "/unblock", Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation.status").value("READY"));
        send(supervisor, first + "/skip", Map.of("reason", "Not needed"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("Quantities were reported")));
        send(supervisor, otherFirst + "/skip", Map.of("reason", "Bought in"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operation.status").value("CANCELLED"));
        assertThat(JsonPath.<String>read(workOrder(other), "$.data.operations[1].input")).isEqualTo("5.000000");

        // Comments and photographs go on the operation's record.
        send(colleague, first + "/comments", Map.of("comment", "Burr on the left face"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events[-1].comment").value("Burr on the left face"));
        mvc.perform(multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", "burr.pdf", "application/pdf",
                                "%PDF-1.7 photo".getBytes(StandardCharsets.UTF_8)))
                        .param("entityType", "WORK_ORDER_OPERATION")
                        .param("entityId", operation(order, 0))
                        .header(HttpHeaders.AUTHORIZATION, colleague.bearer()))
                .andExpect(status().isCreated());
        mvc.perform(multipart("/api/v1/attachments")
                        .file(new MockMultipartFile("file", "burr.pdf", "application/pdf",
                                "%PDF-1.7 photo".getBytes(StandardCharsets.UTF_8)))
                        .param("entityType", "WORK_ORDER_OPERATION")
                        .param("entityId", operation(order, 0))
                        .header(HttpHeaders.AUTHORIZATION, viewer.bearer()))
                .andExpect(status().isForbidden());

        // The floor list: open operations of running orders, and each operator's own.
        String mine = body(as(colleague, get(API + "/operations?mine=true")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(mine, "$.data.items[*].id")).containsExactly(operation(order, 0));
        String all = body(as(viewer, get(API + "/operations?facilityId=" + facility)).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(all, "$.data.items[*].status")).first().isEqualTo("IN_PROGRESS");
        send(viewer, first + "/comments", Map.of("comment", "Hi")).andExpect(status().isForbidden());

        // Other plants and other organisations see nothing.
        Session limited = addMember(owner, "PRODUCTION_MANAGER");
        limit(limited, facility(owner));
        as(limited, get(API + first)).andExpect(status().isNotFound());
        send(limited, first + "/pause", Map.of("category", "OTHER")).andExpect(status().isNotFound());
        assertThat(JsonPath.<List<String>>read(body(as(limited, get(API + "/operations"))), "$.data.items"))
                .isEmpty();
        Session stranger = register();
        as(stranger, get(API + first)).andExpect(status().isNotFound());
    }

    @Test
    void reportsArrivingTogetherNeverGoPastTheQuantity() throws Exception {
        String order = order("10");
        send(supervisor, "/work-orders/" + order + "/start", Map.of()).andExpect(status().isOk());
        String first = operation(order, 0);
        send(operator, "/operations/" + first + "/start", Map.of()).andExpect(status().isOk());

        List<Callable<Integer>> attempts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            attempts.add(() -> send(operator, "/operations/" + first + "/reports", report("3", "3", "0", "0"))
                    .andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            for (Future<Integer> result : pool.invokeAll(attempts)) {
                statuses.add(result.get());
            }
        }
        assertThat(statuses).containsOnly(200, 422);
        assertThat(statuses.stream().filter(code -> code == 200)).hasSize(3);
        assertThat(JsonPath.<String>read(detail(first), "$.data.operation.goodQuantity")).isEqualTo("9.000000");
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT sum(good_quantity) FROM operation_reports WHERE operation_id = ?::uuid",
                java.math.BigDecimal.class, first)).isEqualByComparingTo("9");
    }
}
