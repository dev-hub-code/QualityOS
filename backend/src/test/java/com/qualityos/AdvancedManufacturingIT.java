package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.ResultActions;

/** Downtime, scheduling against capacity, production costing and production analytics. */
class AdvancedManufacturingIT extends TraceabilityFixture {

    private static final String API = "/api/v1/manufacturing";

    private Session supervisor;
    private Session operator;
    private Session planner;
    private String facility;
    private String material;
    private String product;
    private String centre;
    private String machine;

    /** A shaft takes 1 kg of bar and an hour of turning on one machine; the plant works 08:00 to 16:00. */
    @BeforeEach
    void plant() throws Exception {
        supervisor = addMember(owner, "PRODUCTION_MANAGER");
        operator = addMember(owner, "PRODUCTION_OPERATOR");
        planner = addMember(owner, "PRODUCTION_PLANNER");
        facility = facility(owner);
        as(owner, postJson("/api/v1/facilities/" + facility + "/shifts", Map.of("name", "Day",
                "startsAt", "08:00", "endsAt", "16:00", "breakMinutes", 30, "daysOfWeek", 127)))
                .andExpect(status().isCreated());
        material = material(owner, false, "KG");
        product = idOf(body(as(owner, postJson("/api/v1/products", Map.of("code", code(), "name", "Shaft",
                        "productType", "FINISHED", "uomId", unitId(owner, "PCS"))))
                .andExpect(status().isCreated())));
        String bom = JsonPath.read(body(send(owner, "/boms", Map.of("productId", product))
                .andExpect(status().isCreated())), "$.data.revision.id");
        send(owner, "/boms/" + bom + "/lines", Map.of("materialId", material, "quantity", "1",
                "uomId", unitId(owner, "KG"))).andExpect(status().isOk());
        approve("/boms", bom);
        centre = idOf(body(send(owner, "/work-centers", Map.of("code", "WC-" + code(), "name", "Turning",
                "facilityId", facility, "labourRate", "600", "overheadRate", "120"))
                .andExpect(status().isCreated())));
        machine = idOf(body(send(owner, "/machines", Map.of("code", "M-" + code(), "name", "Lathe",
                "workCenterId", centre)).andExpect(status().isCreated())));
        String routing = JsonPath.read(body(send(owner, "/routings", Map.of("productId", product))
                .andExpect(status().isCreated())), "$.data.revision.id");
        send(owner, "/routings/" + routing + "/operations", Map.of("sequence", 10, "name", "Turn",
                "workCenterId", centre, "setupMinutes", "0", "runMinutes", "60")).andExpect(status().isOk());
        approve("/routings", routing);
    }

    private void approve(String path, String id) throws Exception {
        send(owner, path + "/" + id + "/submit", Map.of()).andExpect(status().isOk());
        send(manager, path + "/" + id + "/approve", Map.of()).andExpect(status().isOk());
    }

    private ResultActions send(Session session, String path, Object body) throws Exception {
        return as(session, postJson(API + path, body));
    }

    private String approved(String quantity, LocalDate start, LocalDate end) throws Exception {
        String order = JsonPath.read(body(send(owner, "/work-orders", Map.of("productId", product,
                        "facilityId", facility, "quantity", quantity, "plannedStart", start.toString(),
                        "plannedEnd", end.toString())).andExpect(status().isCreated())), "$.data.workOrder.id");
        send(owner, "/work-orders/" + order + "/submit", Map.of()).andExpect(status().isOk());
        send(supervisor, "/work-orders/" + order + "/approve", Map.of()).andExpect(status().isOk());
        return order;
    }

    private String started(String quantity) throws Exception {
        String order = approved(quantity, LocalDate.now(), LocalDate.now().plusDays(5));
        send(supervisor, "/work-orders/" + order + "/release", Map.of()).andExpect(status().isOk());
        send(supervisor, "/work-orders/" + order + "/start", Map.of()).andExpect(status().isOk());
        return order;
    }

    private String operation(String order) throws Exception {
        return "/operations/" + JsonPath.<String>read(body(as(owner, get(API + "/work-orders/" + order))),
                "$.data.operations[0].id");
    }

    private String downtime(Session session, String query) throws Exception {
        return body(as(session, get(API + "/downtime?" + query)).andExpect(status().isOk()));
    }

    /** A priced lot of the bar: 50 per kg. */
    private String pricedLot(String quantity) throws Exception {
        Map<String, Object> request = new HashMap<>(orderRequest(approvedSupplier(owner), facility, material,
                quantity, null));
        request.put("items", List.of(Map.of("materialId", material, "quantity", quantity, "unitPrice", "50")));
        String created = body(as(owner, postJson("/api/v1/purchase-orders", request)).andExpect(status().isCreated()));
        String order = idOf(created);
        as(owner, postJson("/api/v1/purchase-orders/" + order + "/issue", Map.of())).andExpect(status().isOk());
        return JsonPath.read(body(receive(owner, new IssuedOrder(order, JsonPath.read(created, "$.data.items[0].id")),
                quantity).andExpect(status().isCreated())), "$.data.items[0].id");
    }

    @Test
    void downtimeIsRecordedFromTheFloorFromMachinesAndByHand() throws Exception {
        String order = started("5");
        String path = operation(order);
        send(operator, path + "/start", Map.of("machineId", machine)).andExpect(status().isOk());

        // A breakdown on the floor opens a stop on the operation and machine; resuming ends it.
        send(operator, path + "/pause", Map.of("category", "MACHINE_BREAKDOWN", "reason", "Spindle seized"))
                .andExpect(status().isOk());
        String open = downtime(owner, "open=true&workOrderId=" + order);
        assertThat(JsonPath.<List<String>>read(open, "$.data.items[*].source")).containsExactly("SHOP_FLOOR");
        assertThat(JsonPath.<String>read(open, "$.data.items[0].category")).isEqualTo("MACHINE_BREAKDOWN");
        assertThat(JsonPath.<String>read(open, "$.data.items[0].machineId")).isEqualTo(machine);
        assertThat(JsonPath.<String>read(open, "$.data.items[0].reason")).isEqualTo("Spindle seized");
        String floorStop = JsonPath.read(open, "$.data.items[0].id");
        send(supervisor, "/downtime/" + floorStop + "/end", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("operation is resumed")));
        send(owner, "/machines/" + machine + "/status", Map.of("status", "AVAILABLE")).andExpect(status().isOk());
        send(operator, path + "/resume", Map.of()).andExpect(status().isOk());
        assertThat(JsonPath.<List<String>>read(downtime(owner, "open=true&workOrderId=" + order), "$.data.items"))
                .isEmpty();
        assertThat(JsonPath.<String>read(body(as(owner, get(API + "/downtime/" + floorStop))), "$.data.endedAt"))
                .isNotNull();

        // Only who was responsible, the reason and comments change on a stop recorded as it happened.
        as(supervisor, put(API + "/downtime/" + floorStop).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("category", "OTHER"))))
                .andExpect(status().isUnprocessableContent());
        as(supervisor, put(API + "/downtime/" + floorStop).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("responsibleDepartment", "Maintenance"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.responsibleDepartment").value("Maintenance"));
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                "UPDATE downtime_records SET ended_at = started_at WHERE id = ?::uuid", floorStop)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                "DELETE FROM downtime_records WHERE id = ?::uuid", floorStop)))
                .isInstanceOf(DataAccessException.class);

        // A machine set to maintenance by hand is down until it is set back.
        send(owner, "/machines/" + machine + "/status", Map.of("status", "MAINTENANCE", "reason", "Oil change"))
                .andExpect(status().isOk());
        String machineStops = downtime(owner, "open=true&machineId=" + machine);
        assertThat(JsonPath.<List<String>>read(machineStops, "$.data.items[*].source")).containsExactly("MACHINE");
        assertThat(JsonPath.<Boolean>read(machineStops, "$.data.items[0].planned")).isTrue();
        send(owner, "/machines/" + machine + "/status", Map.of("status", "AVAILABLE")).andExpect(status().isOk());
        assertThat(JsonPath.<List<String>>read(downtime(owner, "open=true&machineId=" + machine), "$.data.items"))
                .isEmpty();

        // By hand: only with DOWNTIME_MANAGE; only planned maintenance ahead, and with an end.
        Instant hourAgo = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> power = new HashMap<>(Map.of("workCenterId", centre, "category", "POWER_FAILURE",
                "startedAt", hourAgo.toString(), "endedAt", hourAgo.plus(20, ChronoUnit.MINUTES).toString(),
                "reason", "Grid outage", "responsibleDepartment", "Electrical"));
        send(operator, "/downtime", power).andExpect(status().isForbidden());
        String manual = idOf(body(send(supervisor, "/downtime", power).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.minutes").value("20.0"))
                .andExpect(jsonPath("$.data.planned").value(false))));
        power.put("startedAt", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        power.remove("endedAt");
        send(supervisor, "/downtime", power)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("Only planned maintenance can be entered ahead of time."));
        Map<String, Object> maintenance = new HashMap<>(Map.of("machineId", machine,
                "category", "PLANNED_MAINTENANCE", "startedAt", Instant.now().plus(1, ChronoUnit.DAYS).toString(),
                "reason", "Annual service"));
        send(supervisor, "/downtime", maintenance)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("Say when the planned maintenance ends."));
        maintenance.put("endedAt", Instant.now().plus(1, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS).toString());
        send(supervisor, "/downtime", maintenance).andExpect(status().isCreated());
        send(supervisor, "/downtime", Map.of("workCenterId", centre, "category", "ORDER_ON_HOLD",
                "startedAt", hourAgo.toString(), "reason", "x"))
                .andExpect(status().isUnprocessableContent());
        send(supervisor, "/downtime", Map.of("category", "OTHER", "startedAt", hourAgo.toString(), "reason", "x"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("Say which machine or work centre stopped."));
        // A stop entered by hand can be corrected and ended.
        as(supervisor, put(API + "/downtime/" + manual).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("endedAt",
                                hourAgo.plus(30, ChronoUnit.MINUTES).toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minutes").value("30.0"));

        // Summaries by category and by machine.
        String today = LocalDate.now().toString();
        String byCategory = body(as(owner, get(API + "/downtime/summary?groupBy=CATEGORY&from=" + today + "&to="
                + today + "&facilityId=" + facility)).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(byCategory, "$.data.rows[*].key"))
                .contains("POWER_FAILURE", "MACHINE_BREAKDOWN", "PLANNED_MAINTENANCE");
        assertThat(JsonPath.<List<String>>read(byCategory, "$.data.rows[?(@.key == 'POWER_FAILURE')].minutes"))
                .containsExactly("30.0");
        String byMachine = body(as(owner, get(API + "/downtime/summary?groupBy=MACHINE&from=" + today + "&to="
                + today)).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(byMachine, "$.data.rows[*].label")).contains("No machine");
        assertThat(asDatabaseSuperuser().queryForList("SELECT action FROM audit_logs WHERE entity_id = ?",
                String.class, manual)).contains("DOWNTIME_RECORDED", "DOWNTIME_UPDATED");

        // Other facilities and organisations see none of it.
        Session limited = addMember(owner, "PRODUCTION_MANAGER");
        limit(limited, facility(owner));
        as(limited, get(API + "/downtime/" + manual)).andExpect(status().isNotFound());
        assertThat(JsonPath.<List<String>>read(downtime(limited, ""), "$.data.items")).isEmpty();
        Session stranger = register();
        as(stranger, get(API + "/downtime/" + manual)).andExpect(status().isNotFound());
    }

    @Test
    void capacityIsCheckedAndCostAndAnalyticsComeFromWhatWasRecorded() throws Exception {
        LocalDate day = LocalDate.now().plusDays(1);
        // Ten shafts need 600 minutes of turning; one machine gives 450 a day.
        String order = approved("10", day, day);
        String query = "/scheduling?facilityId=" + facility + "&from=" + day + "&to=" + day.plusDays(1);
        String board = body(as(planner, get(API + query)).andExpect(status().isOk()));
        assertThat(JsonPath.<Boolean>read(board, "$.data.shiftsDefined")).isTrue();
        assertThat(JsonPath.<String>read(board, "$.data.workCenters[0].days[0].capacityMinutes")).isEqualTo("450.0");
        assertThat(JsonPath.<String>read(board, "$.data.workCenters[0].days[0].loadMinutes")).isEqualTo("600.0");
        assertThat(JsonPath.<List<Map<String, Object>>>read(board, "$.data.conflicts")).hasSize(1);
        assertThat(JsonPath.<List<String>>read(board, "$.data.orders[0].warnings"))
                .anyMatch(warning -> warning.startsWith("It needs 10.0 h at"))
                .anyMatch(warning -> warning.startsWith("Over capacity:"));

        // Rescheduled over two days, it fits.
        Map<String, Object> move = Map.of("plannedStart", day.toString(), "plannedEnd", day.plusDays(1).toString(),
                "priority", "HIGH", "reason", "Spread over two shifts");
        send(operator, "/work-orders/" + order + "/reschedule", move).andExpect(status().isForbidden());
        send(planner, "/work-orders/" + order + "/reschedule", move).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.priority").value("HIGH"))
                .andExpect(jsonPath("$.data.order.warnings").isEmpty());
        assertThat(JsonPath.<List<Object>>read(body(as(planner, get(API + query))), "$.data.conflicts")).isEmpty();

        // Planned maintenance ahead takes time out of capacity.
        Instant maintenanceStart = day.atTime(9, 0).atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
        send(supervisor, "/downtime", Map.of("machineId", machine, "category", "PLANNED_MAINTENANCE",
                "startedAt", maintenanceStart.toString(),
                "endedAt", maintenanceStart.plus(2, ChronoUnit.HOURS).toString(), "reason", "Service"))
                .andExpect(status().isCreated());
        board = body(as(planner, get(API + query)));
        assertThat(JsonPath.<String>read(board, "$.data.workCenters[0].days[0].capacityMinutes")).isEqualTo("330.0");
        assertThat(JsonPath.<String>read(board, "$.data.workCenters[0].days[0].plannedDowntimeMinutes"))
                .isEqualTo("120.0");

        // Cost: planned from the purchase price and rates; actual from the lots issued and running time.
        String running = started("2");
        String lot = pricedLot("10");
        send(supervisor, "/work-orders/" + running + "/issues", Map.of("lineNumber", 1, "lotId", lot,
                "quantity", "2")).andExpect(status().isOk());
        String path = operation(running);
        send(operator, path + "/start", Map.of("machineId", machine)).andExpect(status().isOk());
        send(operator, path + "/reports", Map.of("quantity", "2", "goodQuantity", "1", "rejectedQuantity", "0",
                "scrapQuantity", "1", "reworkQuantity", "0")).andExpect(status().isOk());
        send(operator, path + "/complete", Map.of("reason", "One scrapped")).andExpect(status().isOk());
        as(operator, get(API + "/costing/work-orders/" + running)).andExpect(status().isForbidden());
        String cost = body(as(planner, get(API + "/costing/work-orders/" + running)).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(cost, "$.data.currency")).isEqualTo("INR");
        assertThat(JsonPath.<String>read(cost, "$.data.planned.material")).isEqualTo("100.00");
        assertThat(JsonPath.<String>read(cost, "$.data.actual.material")).isEqualTo("100.00");
        // 120 planned minutes at 600 labour and 120 overhead an hour.
        assertThat(JsonPath.<String>read(cost, "$.data.planned.labour")).isEqualTo("1200.00");
        assertThat(JsonPath.<String>read(cost, "$.data.planned.overhead")).isEqualTo("240.00");
        assertThat(JsonPath.<String>read(cost, "$.data.planned.total")).isEqualTo("1540.00");
        assertThat(JsonPath.<String>read(cost, "$.data.planned.unit")).isEqualTo("770.00");
        assertThat(JsonPath.<Boolean>read(cost, "$.data.complete")).isFalse();
        assertThat(JsonPath.<List<String>>read(cost, "$.data.missing"))
                .containsExactly("Work centre %s has no machine rate.".formatted(
                        JsonPath.<String>read(cost, "$.data.operations[0].workCenterCode")));
        assertThat(new BigDecimal(JsonPath.<String>read(cost, "$.data.actual.scrap")))
                .isEqualByComparingTo(new BigDecimal(JsonPath.<String>read(cost, "$.data.actual.total"))
                        .divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP));
        assertThat(JsonPath.<String>read(cost, "$.data.actual.unit")).isNull();
        send(owner, "/work-orders/" + order + "/release", Map.of());
        as(planner, get(API + "/costing/work-orders/" + approved("1", day, day)))
                .andExpect(status().isUnprocessableContent());

        // Analytics: units and rates from reports, OEE with every figure explained; cost only with the right.
        String today = LocalDate.now().toString();
        String analytics = body(as(supervisor, get(API + "/analytics?from=" + today + "&to=" + today
                + "&facilityId=" + facility)).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(analytics, "$.data.quality.processed")).isEqualTo("2.000000");
        assertThat(JsonPath.<String>read(analytics, "$.data.quality.scrapRate")).isEqualTo("50.0");
        assertThat(JsonPath.<String>read(analytics, "$.data.machines[0].quality")).isEqualTo("50.0");
        assertThat(JsonPath.<String>read(analytics, "$.data.machines[0].shiftMinutes")).isEqualTo("450.0");
        assertThat(JsonPath.<Integer>read(analytics, "$.data.efficiency.operations")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(analytics, "$.data.workOrderStatus[*].status"))
                .contains("APPROVED", "IN_PROGRESS");
        assertThat(JsonPath.<Object>read(analytics, "$.data.cost")).isNotNull();
        as(manager, get(API + "/analytics?from=" + today + "&to=" + today)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cost").doesNotExist());
        as(operator, get(API + "/analytics?from=" + today + "&to=" + today)).andExpect(status().isForbidden());
        as(supervisor, get(API + "/analytics?from=" + today + "&to=" + LocalDate.now().plusYears(2)))
                .andExpect(status().isUnprocessableContent());
    }
}
