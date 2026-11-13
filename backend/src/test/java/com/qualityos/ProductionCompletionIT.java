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
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.ResultActions;

/** Receiving a work order's finished goods into its batch, and closing the order. */
class ProductionCompletionIT extends TraceabilityFixture {

    private static final String API = "/api/v1/manufacturing";

    private Session supervisor;
    private Session operator;
    private String facility;
    private String material;
    private String product;

    /** A shaft takes 1 kg of bar, and is turned, then ground. */
    @BeforeEach
    void shaft() throws Exception {
        supervisor = addMember(owner, "PRODUCTION_MANAGER");
        operator = addMember(owner, "PRODUCTION_OPERATOR");
        facility = facility(owner);
        material = material(owner, false, "KG");
        product = idOf(body(as(owner, postJson("/api/v1/products", Map.of("code", code(), "name", "Shaft",
                        "productType", "FINISHED", "uomId", unitId(owner, "PCS"))))
                .andExpect(status().isCreated())));
        String bom = JsonPath.read(body(send(owner, "/boms", Map.of("productId", product))
                .andExpect(status().isCreated())), "$.data.revision.id");
        send(owner, "/boms/" + bom + "/lines", Map.of("materialId", material, "quantity", "1",
                "uomId", unitId(owner, "KG"))).andExpect(status().isOk());
        approve("/boms", bom);
        String centre = idOf(body(send(owner, "/work-centers", Map.of("code", "WC-" + code(), "name", "Turning",
                "facilityId", facility)).andExpect(status().isCreated())));
        String routing = JsonPath.read(body(send(owner, "/routings", Map.of("productId", product))
                .andExpect(status().isCreated())), "$.data.revision.id");
        send(owner, "/routings/" + routing + "/operations", Map.of("sequence", 10, "name", "Turn",
                "workCenterId", centre, "setupMinutes", "5", "runMinutes", "1")).andExpect(status().isOk());
        send(owner, "/routings/" + routing + "/operations", Map.of("sequence", 20, "name", "Grind",
                "workCenterId", centre, "setupMinutes", "5", "runMinutes", "1")).andExpect(status().isOk());
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

    private void settings(Map<String, Object> rules) throws Exception {
        Map<String, Object> request = new HashMap<>(Map.of("manufacturingModel", "DISCRETE", "separateApprover", true));
        request.putAll(rules);
        as(owner, put(API + "/settings").contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    /** A released lot of the bar, received into the facility. */
    private String lotIn(String quantity) throws Exception {
        String created = body(as(owner, postJson("/api/v1/purchase-orders",
                        orderRequest(approvedSupplier(owner), facility, material, quantity, null)))
                .andExpect(status().isCreated()));
        String order = idOf(created);
        as(owner, postJson("/api/v1/purchase-orders/" + order + "/issue", Map.of())).andExpect(status().isOk());
        return JsonPath.read(body(receive(owner, new IssuedOrder(order, JsonPath.read(created, "$.data.items[0].id")),
                quantity).andExpect(status().isCreated())), "$.data.items[0].id");
    }

    /** An order released and started. */
    private String started(String quantity) throws Exception {
        String order = JsonPath.read(body(send(owner, "/work-orders", Map.of("productId", product,
                        "facilityId", facility, "quantity", quantity, "plannedStart", TODAY,
                        "plannedEnd", LocalDate.now().plusDays(2).toString())).andExpect(status().isCreated())),
                "$.data.workOrder.id");
        send(owner, "/work-orders/" + order + "/submit", Map.of()).andExpect(status().isOk());
        send(supervisor, "/work-orders/" + order + "/approve", Map.of()).andExpect(status().isOk());
        send(supervisor, "/work-orders/" + order + "/release", Map.of()).andExpect(status().isOk());
        send(supervisor, "/work-orders/" + order + "/start", Map.of()).andExpect(status().isOk());
        return order;
    }

    /** Runs an operation: started, one report, completed. */
    private void run(String order, int index, String quantity, String good, String scrap) throws Exception {
        String operation = JsonPath.read(body(as(owner, get(API + "/work-orders/" + order))),
                "$.data.operations[" + index + "].id");
        String path = "/operations/" + operation;
        send(operator, path + "/start", Map.of()).andExpect(status().isOk());
        send(operator, path + "/reports", Map.of("quantity", quantity, "goodQuantity", good, "rejectedQuantity", "0",
                "scrapQuantity", scrap, "reworkQuantity", "0")).andExpect(status().isOk());
        send(operator, path + "/complete", Map.of("reason", "As reported")).andExpect(status().isOk());
    }

    private String completion(Session session, String order) throws Exception {
        return body(as(session, get(API + "/work-orders/" + order + "/completion")).andExpect(status().isOk()));
    }

    private String batchOf(String order) throws Exception {
        return JsonPath.read(body(as(owner, get(API + "/work-orders/" + order))), "$.data.workOrder.productionBatchId");
    }

    private static BigDecimal number(String body, String path) {
        return new BigDecimal(JsonPath.<String>read(body, path));
    }

    private static List<String> serials(int count) {
        String prefix = "SH-" + System.nanoTime() + "-";
        return IntStream.rangeClosed(1, count).mapToObj(i -> prefix + i).toList();
    }

    @Test
    void finishedGoodsAreReceivedOnceAndAShortOrderClosesAfterItsFinalInspection() throws Exception {
        settings(Map.of("serialTracking", true, "materialVariancePercent", "5"));
        as(manager, postJson(API + "/quality-checkpoints", Map.of("name", "Final", "stage", "FINAL_RELEASE",
                "templateId", template("FQC"), "productId", product))).andExpect(status().isCreated());
        String lot = lotIn("20");
        String order = started("10");
        String path = "/work-orders/" + order;
        String batch = batchOf(order);

        // Nothing is received while operations run and no material went in.
        String view = completion(owner, order);
        assertThat(JsonPath.<List<String>>read(view, "$.data.receiveBlockers"))
                .contains("Operation 10 is ready.", "Operation 20 is pending.",
                        "No material has been issued to the batch, so it could not be traced.");
        assertThat(JsonPath.<Boolean>read(view, "$.data.serialsRequired")).isTrue();
        send(operator, path + "/receipt", Map.of()).andExpect(status().isForbidden());
        send(supervisor, path + "/receipt", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("Finished goods cannot be received yet.")));

        // Ten kilograms go in; nine shafts come out good and one is scrapped.
        send(supervisor, path + "/issues", Map.of("lineNumber", 1, "lotId", lot, "quantity", "10"))
                .andExpect(status().isOk());
        run(order, 0, "10", "9", "1");
        run(order, 1, "9", "9", "0");
        view = completion(owner, order);
        assertThat(JsonPath.<List<String>>read(view, "$.data.receiveBlockers")).isEmpty();
        assertThat(number(view, "$.data.expectedQuantity")).isEqualByComparingTo("9");
        assertThat(number(view, "$.data.scrapQuantity")).isEqualByComparingTo("1");
        assertThat(JsonPath.<Boolean>read(view, "$.data.materials[0].outside")).isTrue();

        // The batch is finished through its work order, never directly.
        complete(batch, "9")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("Receive its finished goods there.")));

        // The quantity is what the operations reported; short output, material use and serials are explained.
        send(supervisor, path + "/receipt", Map.of("quantity", "10"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message")
                        .value("The operations turned out 9 good, so that is what is received."));
        send(supervisor, path + "/receipt", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("Only 9 of 10 were made. Say why it is short."));
        Map<String, Object> request = new HashMap<>(Map.of("shortReason", "One scrapped at turning"));
        send(supervisor, path + "/receipt", request)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("Material use is off by more than 5%.")));
        request.put("varianceReason", "Bar for the scrapped shaft");
        send(supervisor, path + "/receipt", request)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("Give one serial number for each of the 9 units."));
        List<String> serials = serials(9);
        List<String> twice = new ArrayList<>(serials.subList(0, 8));
        twice.add(serials.getFirst());
        request.put("serialNumbers", twice);
        send(supervisor, path + "/receipt", request)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("A serial number is given twice."));
        request.put("serialNumbers", serials);
        String received = body(sendWithKey(supervisor, path + "/receipt", request, "fg-1").andExpect(status().isOk()));
        String receipt = JsonPath.read(received, "$.data.receipt.id");
        assertThat(number(received, "$.data.receipt.quantity")).isEqualByComparingTo("9");
        assertThat(JsonPath.<Integer>read(received, "$.data.receipt.serialCount")).isEqualTo(9);
        assertThat(JsonPath.<String>read(received, "$.data.closesAs")).isEqualTo("PARTIALLY_COMPLETED");

        // A retry with the same key returns the same receipt; any other is refused. Nothing is received twice.
        sendWithKey(supervisor, path + "/receipt", request, "fg-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receipt.id").value(receipt));
        sendWithKey(supervisor, path + "/receipt", request, "fg-2")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("were already received")));
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM finished_goods_receipts WHERE work_order_id = ?::uuid", Long.class, order))
                .isEqualTo(1L);
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM finished_products WHERE production_batch_id = ?::uuid", Long.class, batch))
                .isEqualTo(9L);
        String goods = body(as(owner, get("/api/v1/production-batches/" + batch)));
        assertThat(JsonPath.<String>read(goods, "$.data.batch.status")).isEqualTo("COMPLETED");
        assertThat(number(goods, "$.data.batch.producedQuantity")).isEqualByComparingTo("9");

        // Its materials are settled: no more is reserved for it.
        send(supervisor, path + "/reservations/auto", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("so its materials are settled")));

        // Closing waits for the final inspection, then needs a reason because it is short.
        send(supervisor, path + "/complete", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("waiting for its final inspection")));
        String quality = body(as(owner, get(API + path + "/quality")));
        String gate = JsonPath.<List<String>>read(quality, "$.data.gates[?(@.name == 'Final')].id").getFirst();
        String inspection = JsonPath.read(body(send(owner, path + "/quality-gates/" + gate + "/inspections", Map.of())
                .andExpect(status().isOk())), "$.data.inspections[-1].id");
        String inspectionPath = "/api/v1/inspections/" + inspection;
        as(owner, postJson(inspectionPath + "/execute", Map.of("results", List.of(
                Map.of("parameterCode", "DIA", "sampleNumber", 1, "valueNumeric", "20.0"))))).andExpect(status().isOk());
        as(owner, postJson(inspectionPath + "/submit", Map.of())).andExpect(status().isOk());
        as(manager, postJson(inspectionPath + "/approve", Map.of())).andExpect(status().isOk());
        as(manager, postJson(inspectionPath + "/disposition", Map.of("disposition", "ACCEPT")))
                .andExpect(status().isOk());
        assertThat(statusOf(batch)).isEqualTo("RELEASED");
        send(operator, path + "/complete", Map.of()).andExpect(status().isForbidden());
        send(supervisor, path + "/complete", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("%s closes short: 9 received of 10. Say why."
                        .formatted(JsonPath.<String>read(body(as(owner, get(API + path))),
                                "$.data.workOrder.workOrderNumber"))));
        String closed = body(send(supervisor, path + "/complete", Map.of("reason", "Scrap at turning"))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(closed, "$.data.closesAs")).isEqualTo("PARTIALLY_COMPLETED");
        assertThat(JsonPath.<String>read(closed, "$.data.completedAt")).isNotNull();
        String detail = body(as(owner, get(API + path)));
        assertThat(JsonPath.<String>read(detail, "$.data.workOrder.status")).isEqualTo("PARTIALLY_COMPLETED");
        assertThat(JsonPath.<List<String>>read(detail, "$.data.history[*].toStatus").getLast())
                .isEqualTo("PARTIALLY_COMPLETED");
        send(supervisor, path + "/complete", Map.of("reason", "Again"))
                .andExpect(status().isUnprocessableContent());
        send(supervisor, path + "/hold", Map.of("reason", "x")).andExpect(status().isUnprocessableContent());

        // The closed order and its receipt are history.
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                "UPDATE work_orders SET notes = 'x' WHERE id = ?::uuid", order)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                "UPDATE finished_goods_receipts SET short_reason = 'x' WHERE id = ?::uuid", receipt)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                "DELETE FROM finished_goods_receipts WHERE id = ?::uuid", receipt)))
                .isInstanceOf(DataAccessException.class);
        assertThat(asDatabaseSuperuser().queryForList(
                "SELECT action FROM audit_logs WHERE entity_id = ?", String.class, order))
                .contains("WORK_ORDER_FINISHED_GOODS_RECEIVED", "WORK_ORDER_PARTIALLY_COMPLETED");

        // The batch's production record names the order, how it was run, its gates and its receipt.
        String record = body(as(owner, get(API + "/work-orders/production-record?batchId=" + batch))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(record, "$.data.workOrderId")).isEqualTo(order);
        assertThat(JsonPath.<String>read(record, "$.data.definitionKind")).isEqualTo("BOM");
        assertThat(JsonPath.<List<String>>read(record, "$.data.operations[*].name")).containsExactly("Turn", "Grind");
        assertThat(JsonPath.<List<String>>read(record, "$.data.operations[0].operators")).hasSize(1);
        assertThat(JsonPath.<List<String>>read(record, "$.data.gates")).hasSize(1);
        assertThat(JsonPath.<String>read(record, "$.data.gates[0]")).startsWith("Final: passed");
        assertThat(number(record, "$.data.receipt.quantity")).isEqualByComparingTo("9");
        String byHand = releasedBatch(releasedLot("5").id(), "5", "5");
        as(owner, get(API + "/work-orders/production-record?batchId=" + byHand)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void excessOutputClosesInFullOnceAndOnlyWhereTheCallerMayWork() throws Exception {
        settings(Map.of("completionRequiresFinalInspection", false, "overReportPercent", "10", "allowOverIssue", true,
                "completionTolerancePercent", "10"));
        String lot = lotIn("20");
        String order = started("10");
        String path = "/work-orders/" + order;
        send(supervisor, path + "/issues", Map.of("lineNumber", 1, "lotId", lot, "quantity", "11"))
                .andExpect(status().isOk());
        run(order, 0, "11", "11", "0");
        run(order, 1, "11", "11", "0");

        // Others see none of it: another facility, another organisation.
        Session limited = addMember(owner, "PRODUCTION_MANAGER");
        limit(limited, facility(owner));
        as(limited, get(API + path + "/completion")).andExpect(status().isNotFound());
        send(limited, path + "/receipt", Map.of()).andExpect(status().isNotFound());
        Session stranger = register();
        as(stranger, get(API + path + "/completion")).andExpect(status().isNotFound());
        as(stranger, get(API + "/work-orders/production-record?batchId=" + batchOf(order)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());

        // Two receipts at once: one wins, the other finds it received.
        List<Callable<Integer>> attempts = new ArrayList<>();
        for (String key : List.of("a", "b")) {
            attempts.add(() -> sendWithKey(supervisor, path + "/receipt", Map.of(), "race-" + key)
                    .andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (Future<Integer> result : pool.invokeAll(attempts)) {
                statuses.add(result.get());
            }
        }
        assertThat(statuses).containsExactlyInAnyOrder(200, 422);

        // Eleven of ten is within the over-report allowance, so it closes completed, without a reason.
        String view = completion(owner, order);
        assertThat(number(view, "$.data.receipt.quantity")).isEqualByComparingTo("11");
        assertThat(JsonPath.<String>read(view, "$.data.closesAs")).isEqualTo("COMPLETED");
        send(supervisor, path + "/complete", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closesAs").value("COMPLETED"));
        assertThat(JsonPath.<String>read(body(as(owner, get(API + path))), "$.data.workOrder.status"))
                .isEqualTo("COMPLETED");
        assertThat(asDatabaseSuperuser().queryForList(
                "SELECT action FROM audit_logs WHERE entity_id = ?", String.class, order))
                .contains("WORK_ORDER_COMPLETED");
    }
}
