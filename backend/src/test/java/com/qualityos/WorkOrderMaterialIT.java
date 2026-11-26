package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.common.util.DayLabels;
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
        finalInspectionTemplate();
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
        return released(product, quantity);
    }

    private String released(String productId, String quantity) throws Exception {
        String order = JsonPath.read(body(send(owner, "/work-orders", Map.of("productId", productId,
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

    /** A lot of the material received on {@code receivedOn}, expiring on {@code expiry} (none when null). */
    private ResultActions receiveExpiring(String materialId, String quantity, LocalDate receivedOn, LocalDate expiry)
            throws Exception {
        String created = body(as(owner, postJson("/api/v1/purchase-orders",
                        orderRequest(approvedSupplier(owner), facility, materialId, quantity, null)))
                .andExpect(status().isCreated()));
        String order = idOf(created);
        as(owner, postJson("/api/v1/purchase-orders/" + order + "/issue", Map.of())).andExpect(status().isOk());
        Map<String, Object> item = new HashMap<>(Map.of("purchaseOrderItemId",
                JsonPath.read(created, "$.data.items[0].id"), "supplierBatch", "HEAT-7", "quantity", quantity));
        if (expiry != null) {
            item.put("expiryDate", expiry.toString());
        }
        return as(owner, postJson("/api/v1/material-receipts", Map.of("purchaseOrderId", order,
                "receivedOn", receivedOn.toString(), "items", List.of(item))));
    }

    private String lotExpiring(String quantity, LocalDate receivedOn, LocalDate expiry) throws Exception {
        return JsonPath.read(body(receiveExpiring(material, quantity, receivedOn, expiry)
                .andExpect(status().isCreated())), "$.data.items[0].id");
    }

    @Test
    void lotsAreTakenFirstExpiringFirstAndExpiredOnesNotAtAll() throws Exception {
        LocalDate today = LocalDate.now();
        String old = lotExpiring("15", today.minusDays(20), null);
        String soon = lotExpiring("15", today, today.plusDays(5));
        String expired = lotExpiring("50", today.minusDays(10), today.minusDays(2));
        // A lot cannot arrive already past its expiry.
        receiveExpiring(material, "5", today, today.minusDays(1))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("before it was received")));

        String order = released("10");
        String view = materials(order);
        assertThat(JsonPath.<List<String>>read(view, "$.data.lines[0].sources[*].id")).containsExactly(soon, old);
        assertThat(JsonPath.<String>read(view, "$.data.lines[0].sources[0].expiryDate"))
                .isEqualTo(today.plusDays(5).toString());
        assertThat(JsonPath.<String>read(view, "$.data.lines[0].available")).isEqualTo("30.000000");

        // Twenty-two needed: all of the lot expiring soonest, then the rest from the oldest.
        send(approver, "/work-orders/" + order + "/reservations/auto", Map.of()).andExpect(status().isOk());
        assertThat(lotColumn(soon, "reserved_quantity")).isEqualByComparingTo("15");
        assertThat(lotColumn(old, "reserved_quantity")).isEqualByComparingTo("7");
        assertThat(lotColumn(expired, "reserved_quantity")).isEqualByComparingTo("0");

        // Expired stock is refused however it is asked for.
        String other = released("10");
        send(approver, "/work-orders/" + other + "/reservations", Map.of("lineNumber", 1, "lotId", expired,
                "quantity", "1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message")
                        .value(containsString("expired on " + DayLabels.label(today.minusDays(2)))));
        consumeLot(startBatch(product()), expired, "1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("expired on")));

        // A lot reserved before it expired cannot be issued after.
        asDatabaseSuperuser().update("UPDATE material_receipt_items SET expiry_date = ? WHERE id = ?::uuid",
                java.sql.Date.valueOf(today.minusDays(1)), soon);
        send(approver, "/work-orders/" + order + "/start", Map.of()).andExpect(status().isOk());
        send(approver, "/work-orders/" + order + "/issues", Map.of("lineNumber", 1, "lotId", soon, "quantity", "5"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("expired on")));
    }

    @Test
    void aMaterialsShelfLifeGivesEachLotItsExpiry() throws Exception {
        String kept = idOf(body(as(owner, postJson("/api/v1/materials", Map.of("code", code(), "name", "Adhesive",
                        "uomId", unitId(owner, "KG"), "inspectionRequired", false, "shelfLifeDays", 30)))
                .andExpect(status().isCreated())));
        as(owner, get("/api/v1/materials/" + kept)).andExpect(jsonPath("$.data.material.shelfLifeDays").value(30));
        LocalDate received = LocalDate.now().minusDays(3);
        receiveExpiring(kept, "4", received, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items[0].expiryDate").value(received.plusDays(30).toString()));
        // An entered date wins over the shelf life.
        receiveExpiring(kept, "4", received, received.plusDays(7)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items[0].expiryDate").value(received.plusDays(7).toString()));
    }

    @Test
    void aFixedLineIsNeededInFullHoweverMuchIsMade() throws Exception {
        // Two kilograms a unit, plus one kilogram of primer whatever the quantity.
        String primer = material(owner, false, "KG");
        String housing = idOf(body(as(owner, postJson("/api/v1/products", Map.of("code", code(), "name", "Housing",
                        "productType", "FINISHED", "uomId", unitId(owner, "PCS"))))
                .andExpect(status().isCreated())));
        String bom = JsonPath.read(body(send(owner, "/boms", Map.of("productId", housing))
                .andExpect(status().isCreated())), "$.data.revision.id");
        send(owner, "/boms/" + bom + "/lines", Map.of("materialId", material, "quantity", "2",
                "uomId", unitId(owner, "KG"))).andExpect(status().isOk());
        send(owner, "/boms/" + bom + "/lines", Map.of("materialId", primer, "quantity", "1",
                "uomId", unitId(owner, "KG"), "scales", false)).andExpect(status().isOk());
        send(owner, "/boms/" + bom + "/submit", Map.of()).andExpect(status().isOk());
        send(manager, "/boms/" + bom + "/approve", Map.of()).andExpect(status().isOk());
        String bar = lotIn(facility, "100");
        String primerLot = JsonPath.read(body(receiveExpiring(primer, "5", LocalDate.now(), null)
                .andExpect(status().isCreated())), "$.data.items[0].id");

        String order = released(housing, "10");
        assertThat(asDatabaseSuperuser().queryForList("SELECT scales FROM work_order_materials WHERE work_order_id = "
                + "?::uuid ORDER BY line_number", Boolean.class, order)).containsExactly(true, false);
        String path = "/work-orders/" + order;
        send(approver, path + "/start", Map.of()).andExpect(status().isOk());
        sendWithKey(approver, path + "/issues", Map.of("lineNumber", 1, "lotId", bar, "quantity", "16"), "bar")
                .andExpect(status().isOk());
        sendWithKey(approver, path + "/issues", Map.of("lineNumber", 2, "lotId", primerLot, "quantity", "1"), "primer")
                .andExpect(status().isOk());

        // Eight made: sixteen kilograms of bar and the whole kilogram of primer were what they needed.
        String received = body(sendWithKey(approver, path + "/receipt", Map.of("quantity", "8",
                "shortReason", "Two housings were not cast"), "fg").andExpect(status().isOk()));
        assertThat(new BigDecimal(JsonPath.<String>read(received, "$.data.materials[1].expected")))
                .isEqualByComparingTo("1");
        assertThat(JsonPath.<List<Boolean>>read(received, "$.data.materials[*].outside")).containsOnly(false);
        assertThat(asDatabaseSuperuser().queryForObject("SELECT variance_reason FROM finished_goods_receipts "
                + "WHERE work_order_id = ?::uuid", String.class, order)).isNull();
        // A released line keeps how it scales.
        assertThatThrownBy(() -> asDatabaseSuperuser().update("UPDATE work_order_materials SET scales = true "
                + "WHERE work_order_id = ?::uuid AND line_number = 2", order))
                .hasMessageContaining("was released and cannot be rewritten");
    }

    /** A released batch of the product made here, from some of the material. */
    private String releasedHere(String productId) throws Exception {
        String batch = JsonPath.read(body(as(owner, postJson("/api/v1/production-batches", Map.of(
                        "productId", productId, "facilityId", facility, "plannedQuantity", "20")))
                .andExpect(status().isCreated())), "$.data.batch.id");
        consumeLot(batch, lotIn(facility, "10"), "5").andExpect(status().isOk());
        complete(batch, "20").andExpect(status().isOk());
        inspectBatch(batch, "FQC", "20.0", "ACCEPT");
        return batch;
    }

    @Test
    void aSubAssemblyUnderAnOpenRecallIsNotOfferedNorIssued() throws Exception {
        String shaft = product();
        String recalled = releasedHere(shaft);
        String clean = releasedHere(shaft);
        as(owner, postJson("/api/v1/recalls", Map.of("batchNumbers", List.of(numberOf(recalled)),
                "recallDate", TODAY, "reason", "Cracks found in the shaft", "instructions", "Stop use")))
                .andExpect(status().isCreated());
        String housing = idOf(body(as(owner, postJson("/api/v1/products", Map.of("code", code(), "name", "Housing",
                        "productType", "FINISHED", "uomId", unitId(owner, "PCS"))))
                .andExpect(status().isCreated())));
        String bom = JsonPath.read(body(send(owner, "/boms", Map.of("productId", housing))
                .andExpect(status().isCreated())), "$.data.revision.id");
        send(owner, "/boms/" + bom + "/lines", Map.of("componentProductId", shaft, "quantity", "2",
                "uomId", unitId(owner, "PCS"))).andExpect(status().isOk());
        send(owner, "/boms/" + bom + "/submit", Map.of()).andExpect(status().isOk());
        send(manager, "/boms/" + bom + "/approve", Map.of()).andExpect(status().isOk());

        String order = released(housing, "5");
        String view = materials(order);
        assertThat(JsonPath.<List<String>>read(view, "$.data.lines[0].sources[*].id")).containsExactly(clean);
        assertThat(new BigDecimal(JsonPath.<String>read(view, "$.data.lines[0].available")))
                .isEqualByComparingTo("20");
        String path = "/work-orders/" + order;
        send(approver, path + "/start", Map.of()).andExpect(status().isOk());
        sendWithKey(approver, path + "/issues", Map.of("lineNumber", 1, "sourceBatchId", recalled, "quantity", "10"),
                "recalled").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("is under open recall")));
        sendWithKey(approver, path + "/issues", Map.of("lineNumber", 1, "sourceBatchId", clean, "quantity", "10"),
                "clean").andExpect(status().isOk());
    }

    @Test
    void aSubstituteNamedOnTheBomStandsInForTheLineMaterial() throws Exception {
        String substitute = material(owner, false, "KG");
        String counted = material(owner, false, "PCS");
        String housing = idOf(body(as(owner, postJson("/api/v1/products", Map.of("code", code(), "name", "Housing",
                        "productType", "FINISHED", "uomId", unitId(owner, "PCS"))))
                .andExpect(status().isCreated())));
        String bom = JsonPath.read(body(send(owner, "/boms", Map.of("productId", housing))
                .andExpect(status().isCreated())), "$.data.revision.id");
        Map<String, Object> line = new HashMap<>(Map.of("materialId", material, "quantity", "2",
                "uomId", unitId(owner, "KG"), "scrapPercent", "10"));
        line.put("alternateMaterialIds", List.of(material));
        send(owner, "/boms/" + bom + "/lines", line).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("not the line's own material")));
        line.put("alternateMaterialIds", List.of(counted));
        send(owner, "/boms/" + bom + "/lines", line).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("unit of another kind")));
        line.put("alternateMaterialIds", List.of(substitute));
        String saved = body(send(owner, "/boms/" + bom + "/lines", line).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(saved, "$.data.lines[0].alternates[*].materialId"))
                .containsExactly(substitute);
        send(owner, "/boms/" + bom + "/submit", Map.of()).andExpect(status().isOk());
        send(manager, "/boms/" + bom + "/approve", Map.of()).andExpect(status().isOk());

        // Only the substitute is in stock: it is offered and shown, but automatic reservation leaves it to a person.
        String substituteLot = JsonPath.read(body(receiveExpiring(substitute, "30", LocalDate.now(), null)
                .andExpect(status().isCreated())), "$.data.items[0].id");
        String order = released(housing, "10");
        String path = "/work-orders/" + order;
        send(approver, path + "/reservations/auto", Map.of()).andExpect(status().isOk());
        String view = materials(order);
        assertThat(JsonPath.<String>read(view, "$.data.lines[0].reserved")).isEqualTo("0.000000");
        assertThat(JsonPath.<String>read(view, "$.data.lines[0].available")).isEqualTo("0.000000");
        assertThat(JsonPath.<String>read(view, "$.data.lines[0].substituteAvailable")).isEqualTo("30.000000");
        assertThat(JsonPath.<List<String>>read(view, "$.data.lines[0].sources[*].id")).containsExactly(substituteLot);
        assertThat(JsonPath.<String>read(view, "$.data.lines[0].sources[0].substituteCode")).isNotBlank();
        assertThat(JsonPath.<List<String>>read(view, "$.data.shortages").getFirst())
                .contains("approved substitutes free");

        // A person takes the substitute, one for one in the line's unit; an unrelated material is refused.
        send(approver, path + "/reservations", Map.of("lineNumber", 1, "lotId", substituteLot, "quantity", "12"))
                .andExpect(status().isOk());
        String unrelated = JsonPath.read(body(receiveExpiring(counted, "5", LocalDate.now(), null)
                .andExpect(status().isCreated())), "$.data.items[0].id");
        send(approver, path + "/reservations", Map.of("lineNumber", 1, "lotId", unrelated, "quantity", "1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("nor an approved substitute")));

        String lot = lotIn(facility, "30");
        send(approver, path + "/start", Map.of()).andExpect(status().isOk());
        send(approver, path + "/issues", Map.of("lineNumber", 1, "lotId", substituteLot, "quantity", "12"))
                .andExpect(status().isOk());
        send(approver, path + "/issues", Map.of("lineNumber", 1, "lotId", lot, "quantity", "10"))
                .andExpect(status().isOk());
        view = materials(order);
        assertThat(JsonPath.<String>read(view, "$.data.lines[0].issued")).isEqualTo("22.000000");
        assertThat(JsonPath.<String>read(view, "$.data.lines[0].outstanding")).isEqualTo("0.000000");
        assertThat(lotColumn(substituteLot, "consumed_quantity")).isEqualByComparingTo("12");
        assertThat(asDatabaseSuperuser().queryForObject("SELECT count(*) FROM audit_logs WHERE tenant_id = ?::uuid "
                + "AND action = 'WORK_ORDER_MATERIAL_SUBSTITUTED'", Long.class, owner.tenantId())).isEqualTo(2);
        // A material named as a substitute is in use, like the line's own.
        as(owner, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v1/materials/" + substitute))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(allOf(containsString("1 approved BOM or recipe"),
                        containsString("1 open work order"))));
        // Frozen with the rest of the released line.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> asDatabaseSuperuser().update(
                "UPDATE work_order_materials SET alternate_material_ids = '[]' WHERE work_order_id = ?::uuid", order))
                .hasMessageContaining("cannot be rewritten");
    }

    @Test
    void ordersIssuingFromTheSameLotAtOnceNeverTakeMoreThanItHas() throws Exception {
        String lot = lotIn(facility, "20");
        List<String> orders = List.of(released("10"), released("10"));
        for (String order : orders) {
            send(approver, "/work-orders/" + order + "/start", Map.of()).andExpect(status().isOk());
        }
        java.util.concurrent.CyclicBarrier together = new java.util.concurrent.CyclicBarrier(orders.size());
        List<Callable<Integer>> attempts = new ArrayList<>();
        orders.forEach(order -> attempts.add(() -> {
            together.await();
            return send(approver, "/work-orders/" + order + "/issues",
                    Map.of("lineNumber", 1, "lotId", lot, "quantity", "15")).andReturn().getResponse().getStatus();
        }));
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(orders.size())) {
            for (Future<Integer> result : pool.invokeAll(attempts)) {
                statuses.add(result.get());
            }
        }
        // Twenty in the lot: one issue of fifteen goes through, the other finds only five.
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);
        assertThat(lotColumn(lot, "consumed_quantity")).isEqualByComparingTo("15");
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
