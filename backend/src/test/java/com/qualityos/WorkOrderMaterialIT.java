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
import org.springframework.test.web.servlet.ResultActions;

class WorkOrderMaterialIT extends TraceabilityFixture {

    private static final String API = "/api/v1/manufacturing";

    private Session approver;
    private String facility;
    private String material;
    private String product;

    /** A gearbox takes 2 kg of bar with 10% scrap, so ten need 22 kg. */
    @BeforeEach
    void gearbox() throws Exception {
        approver = addMember(owner, "PRODUCTION_MANAGER");
        facility = facility(owner);
        material = material(owner, false, "KG");
        product = idOf(body(as(owner, postJson("/api/v1/products", Map.of("code", code(), "name", "Gearbox",
                        "productType", "FINISHED", "uomId", unitId(owner, "PCS"))))
                .andExpect(status().isCreated())));
        String bom = JsonPath.read(body(send(owner, "/boms", Map.of("productId", product))
                .andExpect(status().isCreated())), "$.data.revision.id");
        send(owner, "/boms/" + bom + "/lines", Map.of("materialId", material, "quantity", "2",
                "uomId", unitId(owner, "KG"), "scrapPercent", "10")).andExpect(status().isOk());
        send(owner, "/boms/" + bom + "/submit", Map.of()).andExpect(status().isOk());
        send(manager, "/boms/" + bom + "/approve", Map.of()).andExpect(status().isOk());
    }

    private ResultActions send(Session session, String path, Object body) throws Exception {
        return as(session, postJson(API + path, body));
    }

    private ResultActions sendWithKey(Session session, String path, Object body, String key) throws Exception {
        return as(session, postJson(API + path, body).header("Idempotency-Key", key));
    }

    /** A released lot of the material, received into the facility. */
    private String lotIn(String where, String quantity) throws Exception {
        String created = body(as(owner, postJson("/api/v1/purchase-orders",
                        orderRequest(approvedSupplier(owner), where, material, quantity, null)))
                .andExpect(status().isCreated()));
        String order = idOf(created);
        as(owner, postJson("/api/v1/purchase-orders/" + order + "/issue", Map.of())).andExpect(status().isOk());
        return JsonPath.read(body(receive(owner, new IssuedOrder(order, JsonPath.read(created, "$.data.items[0].id")),
                quantity).andExpect(status().isCreated())), "$.data.items[0].id");
    }

    private String released(String quantity) throws Exception {
        String order = JsonPath.read(body(send(owner, "/work-orders", Map.of("productId", product,
                        "facilityId", facility, "quantity", quantity, "plannedStart", TODAY,
                        "plannedEnd", LocalDate.now().plusDays(2).toString())).andExpect(status().isCreated())),
                "$.data.workOrder.id");
        send(owner, "/work-orders/" + order + "/submit", Map.of()).andExpect(status().isOk());
        send(approver, "/work-orders/" + order + "/approve", Map.of()).andExpect(status().isOk());
        send(approver, "/work-orders/" + order + "/release", Map.of()).andExpect(status().isOk());
        return order;
    }

    private String materials(String order) throws Exception {
        return body(as(owner, get(API + "/work-orders/" + order + "/materials")).andExpect(status().isOk()));
    }

    private BigDecimal lotColumn(String lot, String column) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT " + column + " FROM material_receipt_items WHERE id = ?::uuid", BigDecimal.class, lot);
    }

    private void settings(Map<String, Object> rules) throws Exception {
        Map<String, Object> request = new HashMap<>(Map.of("manufacturingModel", "DISCRETE", "separateApprover", true));
        request.putAll(rules);
        as(owner, put(API + "/settings").contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    void stockIsReservedIssuedAndReturnedWithoutTouchingBalancesDirectly() throws Exception {
        String first = lotIn(facility, "15");
        String second = lotIn(facility, "20");
        String elsewhere = lotIn(facility(owner), "50");
        String order = released("10");
        String path = "/work-orders/" + order;

        String view = materials(order);
        assertThat(JsonPath.<String>read(view, "$.data.lines[0].required")).isEqualTo("22.000000");
        assertThat(JsonPath.<String>read(view, "$.data.lines[0].available")).isEqualTo("35.000000");
        assertThat(JsonPath.<String>read(view, "$.data.lines[0].shortage")).isEqualTo("0.000000");
        assertThat(JsonPath.<List<String>>read(view, "$.data.lines[0].sources[*].id")).containsExactly(first, second);

        // Reserving takes the oldest lots first, and only what the line needs.
        view = body(send(approver, path + "/reservations/auto", Map.of()).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(view, "$.data.lines[0].reserved")).isEqualTo("22.000000");
        assertThat(lotColumn(first, "reserved_quantity")).isEqualByComparingTo("15");
        assertThat(lotColumn(second, "reserved_quantity")).isEqualByComparingTo("7");
        send(approver, path + "/reservations", Map.of("lineNumber", 1, "lotId", second, "quantity", "1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("Line 1 needs only 0 more."));
        send(approver, path + "/reservations", Map.of("lineNumber", 1, "lotId", elsewhere, "quantity", "1"))
                .andExpect(status().isUnprocessableContent());

        // Reserved stock is out of reach of anything else, even ordinary batch consumption.
        String otherBatch = startBatch(product());
        consumeLot(otherBatch, first, "1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("the rest is reserved")));

        // Material is issued once production has started.
        Map<String, Object> fromFirst = Map.of("lineNumber", 1, "lotId", first, "quantity", "15");
        send(approver, path + "/issues", fromFirst)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("so you cannot take material")));
        String started = body(send(approver, path + "/start", Map.of()).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(started, "$.data.history[*].reason")).allMatch(r -> r == null);

        send(approver, path + "/issues", fromFirst).andExpect(status().isOk());
        assertThat(lotColumn(first, "reserved_quantity")).isEqualByComparingTo("0");
        assertThat(lotColumn(first, "consumed_quantity")).isEqualByComparingTo("15");
        send(approver, path + "/issues", Map.of("lineNumber", 1, "lotId", second, "quantity", "10"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("switched off in Configuration")));

        // A repeated request with the same key is answered, not applied again.
        Map<String, Object> rest = Map.of("lineNumber", 1, "lotId", second, "quantity", "7");
        sendWithKey(approver, path + "/issues", rest, "issue-7").andExpect(status().isOk());
        view = body(sendWithKey(approver, path + "/issues", rest, "issue-7").andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(view, "$.data.lines[0].issued")).isEqualTo("22.000000");
        assertThat(JsonPath.<String>read(view, "$.data.lines[0].outstanding")).isEqualTo("0.000000");
        assertThat(lotColumn(second, "consumed_quantity")).isEqualByComparingTo("7");

        // Returning part of an issue gives it back to the lot; the batch keeps what stayed used.
        String consumption = JsonPath.<List<String>>read(view,
                "$.data.consumptions[?(@.sourceId == '" + second + "')].id").getFirst();
        send(approver, path + "/returns", Map.of("consumptionId", consumption, "quantity", "8", "reason", "x"))
                .andExpect(status().isUnprocessableContent());
        view = body(send(approver, path + "/returns", Map.of("consumptionId", consumption, "quantity", "5",
                        "reason", "Bar left over"))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(view, "$.data.lines[0].issued")).isEqualTo("17.000000");
        assertThat(lotColumn(second, "consumed_quantity")).isEqualByComparingTo("2");
        assertThat(JsonPath.<List<String>>read(view, "$.data.consumptions[*].quantity"))
                .containsExactlyInAnyOrder("15.000000", "2.000000");
        assertThat(JsonPath.<List<String>>read(view, "$.data.movements[*].movementType"))
                .containsExactly("RESERVED", "RESERVED", "ISSUED", "ISSUED", "RETURNED");
        String batch = JsonPath.read(started, "$.data.workOrder.productionBatchId");
        assertThat(JsonPath.<List<String>>read(body(as(owner, get("/api/v1/production-batches/" + batch))),
                "$.data.consumptions[?(@.reversalReason == 'Bar left over')]")).hasSize(1);
        assertThat(JsonPath.<List<String>>read(body(as(owner, get("/api/v1/audit-logs?size=100"))),
                "$.data.items[*].action")).contains("WORK_ORDER_MATERIAL_RESERVED", "WORK_ORDER_MATERIAL_ISSUED",
                "WORK_ORDER_MATERIAL_RETURNED", "BATCH_MATERIAL_RETURNED");

        // The record is kept: movements and reservations are not deleted, and a lot never sets aside more than it has.
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                        "DELETE FROM work_order_material_movements WHERE work_order_id = ?::uuid", order)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                        "DELETE FROM material_reservations WHERE work_order_id = ?::uuid", order)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                        "UPDATE material_receipt_items SET reserved_quantity = quantity WHERE id = ?::uuid", second)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void shortagesAreShownAndStartFollowsTheOrganisationsRule() throws Exception {
        String lot = lotIn(facility, "10");
        settings(Map.of("startRequiresMaterials", true, "reserveOnRelease", true));
        String order = released("10");

        String view = materials(order);
        assertThat(JsonPath.<String>read(view, "$.data.lines[0].reserved")).isEqualTo("10.000000");
        assertThat(JsonPath.<String>read(view, "$.data.lines[0].shortage")).isEqualTo("12.000000");
        assertThat(JsonPath.<List<String>>read(view, "$.data.shortages")).hasSize(1);
        assertThat(lotColumn(lot, "reserved_quantity")).isEqualByComparingTo("10");

        send(approver, "/work-orders/" + order + "/start", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("needs 22 KG, reserved 10 KG")));

        // Allowed to start short, it does so on the record.
        settings(Map.of("startRequiresMaterials", false));
        String started = body(send(approver, "/work-orders/" + order + "/start", Map.of()).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(started, "$.data.history[*].reason").getLast())
                .startsWith("Started short:");
    }

    @Test
    void ordersCompetingForTheSameLotNeverReserveMoreThanItHas() throws Exception {
        String lot = lotIn(facility, "30");
        List<String> orders = List.of(released("10"), released("10"), released("10"));

        List<Callable<Integer>> attempts = new ArrayList<>();
        orders.forEach(order -> attempts.add(() -> send(approver, "/work-orders/" + order + "/reservations/auto",
                Map.of()).andReturn().getResponse().getStatus()));
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            for (Future<Integer> result : pool.invokeAll(attempts)) {
                assertThat(result.get()).isEqualTo(200);
            }
        }
        BigDecimal reserved = BigDecimal.ZERO;
        for (String order : orders) {
            reserved = reserved.add(new BigDecimal(JsonPath.<String>read(materials(order), "$.data.lines[0].reserved")));
        }
        assertThat(reserved).isEqualByComparingTo("30");
        assertThat(lotColumn(lot, "reserved_quantity")).isEqualByComparingTo("30");

        // Cancelling an order gives back what it held; a receipt with reservations cannot be voided.
        String receipt = asDatabaseSuperuser().queryForObject(
                "SELECT receipt_id::text FROM material_receipt_items WHERE id = ?::uuid", String.class, lot);
        as(owner, postJson("/api/v1/material-receipts/" + receipt + "/void", Map.of("reason", "Wrong supplier")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("reserved for work orders")));
        // Which order won the lot depends on timing; cancel one that holds some of it.
        String held = null;
        BigDecimal heldQuantity = BigDecimal.ZERO;
        for (String order : orders) {
            BigDecimal quantity = new BigDecimal(JsonPath.<String>read(materials(order), "$.data.lines[0].reserved"));
            if (quantity.compareTo(heldQuantity) > 0) {
                held = order;
                heldQuantity = quantity;
            }
        }
        send(approver, "/work-orders/" + held + "/cancel", Map.of("reason", "Customer withdrew")).andExpect(status().isOk());
        assertThat(lotColumn(lot, "reserved_quantity")).isEqualByComparingTo(new BigDecimal("30").subtract(heldQuantity));
        assertThat(JsonPath.<String>read(materials(held), "$.data.lines[0].reserved")).isEqualTo("0.000000");
        assertThat(JsonPath.<List<String>>read(materials(held), "$.data.movements[*].movementType")).contains("UNRESERVED");
    }

    @Test
    void reservationsCanBeReleasedAndRightsAreSeparate() throws Exception {
        String lot = lotIn(facility, "40");
        String order = released("10");
        String path = "/work-orders/" + order;
        Session planner = addMember(owner, "PRODUCTION_PLANNER");
        Session viewer = addMember(owner, "VIEWER");

        as(viewer, get(API + path + "/materials")).andExpect(status().isOk());
        send(viewer, path + "/reservations/auto", Map.of()).andExpect(status().isForbidden());
        String view = body(send(planner, path + "/reservations",
                Map.of("lineNumber", 1, "lotId", lot, "quantity", "5")).andExpect(status().isOk()));
        String reservation = JsonPath.read(view, "$.data.reservations[0].id");
        send(planner, path + "/issues", Map.of("lineNumber", 1, "lotId", lot, "quantity", "5"))
                .andExpect(status().isForbidden());

        send(planner, path + "/reservations/" + reservation + "/release", Map.of("reason", "Wrong lot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservations[0].openQuantity").value("0.000000"));
        assertThat(lotColumn(lot, "reserved_quantity")).isEqualByComparingTo("0");
        send(planner, path + "/reservations/" + reservation + "/release", Map.of())
                .andExpect(status().isUnprocessableContent());
        send(planner, path + "/reservations", Map.of("lineNumber", 2, "lotId", lot, "quantity", "5"))
                .andExpect(status().isUnprocessableContent());

        Session limited = addMember(owner, "PRODUCTION_MANAGER");
        limit(limited, facility(owner));
        as(limited, get(API + path + "/materials")).andExpect(status().isNotFound());
        send(limited, path + "/reservations/auto", Map.of()).andExpect(status().isNotFound());
        as(register(), get(API + path + "/materials")).andExpect(status().isNotFound());
    }
}
