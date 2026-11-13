package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.ResultActions;

/** Quality checkpoints and gates on work orders, quality holds, and rework orders. */
class ProductionQualityIT extends TraceabilityFixture {

    private static final String API = "/api/v1/manufacturing";

    private Session supervisor;
    private Session operator;
    private String facility;
    private String product;
    private String centre;
    private String ipqc;

    /** A shaft is turned, then ground, at one work centre. */
    @BeforeEach
    void shaft() throws Exception {
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
        approve("/boms", bom);
        centre = idOf(body(send(owner, "/work-centers", Map.of("code", "WC-" + code(), "name", "Turning",
                "facilityId", facility)).andExpect(status().isCreated())));
        String routing = JsonPath.read(body(send(owner, "/routings", Map.of("productId", product))
                .andExpect(status().isCreated())), "$.data.revision.id");
        send(owner, "/routings/" + routing + "/operations", Map.of("sequence", 10, "name", "Turn",
                "workCenterId", centre, "setupMinutes", "5", "runMinutes", "1")).andExpect(status().isOk());
        send(owner, "/routings/" + routing + "/operations", Map.of("sequence", 20, "name", "Grind",
                "workCenterId", centre, "setupMinutes", "5", "runMinutes", "1")).andExpect(status().isOk());
        approve("/routings", routing);
        ipqc = template("IPQC");
    }

    private void approve(String path, String id) throws Exception {
        send(owner, path + "/" + id + "/submit", Map.of()).andExpect(status().isOk());
        send(manager, path + "/" + id + "/approve", Map.of()).andExpect(status().isOk());
    }

    private ResultActions send(Session session, String path, Object body) throws Exception {
        return as(session, postJson(API + path, body));
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

    private String operation(String order, int index) throws Exception {
        return JsonPath.read(body(as(owner, get(API + "/work-orders/" + order))), "$.data.operations[" + index + "].id");
    }

    private String quality(Session session, String order) throws Exception {
        return body(as(session, get(API + "/work-orders/" + order + "/quality")).andExpect(status().isOk()));
    }

    private String gate(String quality, String name, int sequence) {
        List<String> ids = JsonPath.read(quality,
                "$.data.gates[?(@.name == '" + name + "' && @.operationSequence == " + sequence + ")].id");
        return ids.getFirst();
    }

    private String checkpoint(String name, String stage, String template, Map<String, Object> more) throws Exception {
        Map<String, Object> request = new HashMap<>(Map.of("name", name, "stage", stage, "templateId", template));
        request.putAll(more);
        return idOf(body(send(manager, "/quality-checkpoints", request).andExpect(status().isCreated())));
    }

    /** The owner measures, the quality manager approves and decides. */
    private void decide(String inspection, String value, String disposition) throws Exception {
        String path = "/api/v1/inspections/" + inspection;
        as(owner, postJson(path + "/execute", Map.of("results", List.of(
                        Map.of("parameterCode", "DIA", "sampleNumber", 1, "valueNumeric", value)))))
                .andExpect(status().isOk());
        as(owner, postJson(path + "/submit", Map.of())).andExpect(status().isOk());
        as(manager, postJson(path + "/approve", Map.of())).andExpect(status().isOk());
        as(manager, postJson(path + "/disposition", Map.of("disposition", disposition))).andExpect(status().isOk());
    }

    /** Starts the gate's inspection and returns its id. */
    private String inspectGate(String order, String gate) throws Exception {
        String response = body(send(owner, "/work-orders/" + order + "/quality-gates/" + gate + "/inspections",
                Map.of()).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(response, "$.data.status")).isEqualTo("IN_INSPECTION");
        return JsonPath.read(response, "$.data.inspections[-1].id");
    }

    private Map<String, Object> report(String quantity, String good, String rejected, String rework) {
        return Map.of("quantity", quantity, "goodQuantity", good, "rejectedQuantity", rejected,
                "scrapQuantity", "0", "reworkQuantity", rework);
    }

    @Test
    void gatesHoldTheLineAndReworkClosesOnlyOnReinspection() throws Exception {
        // Checkpoints name an inspection template of the right kind; only quality may set them.
        send(manager, "/quality-checkpoints", Map.of("name", "Wrong", "stage", "OPERATION_START",
                "templateId", template("FQC")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("An operation takes an in-process (IPQC) inspection template."));
        String fqc = template("FQC");
        send(manager, "/quality-checkpoints", Map.of("name", "Wrong", "stage", "FINAL_RELEASE", "templateId", fqc,
                "workCenterId", centre))
                .andExpect(status().isUnprocessableContent());
        send(supervisor, "/quality-checkpoints", Map.of("name", "First-off", "stage", "OPERATION_START",
                "templateId", ipqc)).andExpect(status().isForbidden());
        checkpoint("First-off", "OPERATION_START", ipqc, Map.of("operationSequence", 10));
        String patrol = checkpoint("Patrol", "IN_PROCESS", ipqc, Map.of("workCenterId", centre));
        checkpoint("Final", "FINAL_RELEASE", fqc, Map.of("productId", product));
        checkpoint("Optional", "OPERATION_COMPLETION", ipqc, Map.of("operationSequence", 20, "mandatory", false));

        // Release turns them into gates; later changes to checkpoints never reach the order.
        String order = order("10");
        String quality = quality(owner, order);
        assertThat(JsonPath.<List<String>>read(quality, "$.data.gates[*].name"))
                .containsExactly("First-off", "Patrol", "Patrol", "Optional", "Final");
        as(manager, delete(API + "/quality-checkpoints/" + patrol)).andExpect(status().isNoContent());
        assertThat(JsonPath.<List<String>>read(quality(owner, order), "$.data.gates[*].name")).hasSize(5);
        assertThat(JsonPath.<List<String>>read(quality(owner, order("1")), "$.data.gates[*].name"))
                .containsExactly("First-off", "Optional", "Final");

        // Production starts; the first operation waits for its start check.
        send(supervisor, "/work-orders/" + order + "/start", Map.of()).andExpect(status().isOk());
        String turn = "/operations/" + operation(order, 0);
        send(operator, turn + "/start", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message")
                        .value("Operation 10 cannot be started until its quality checks pass. First-off is pending."));
        quality = quality(owner, order);
        send(owner, "/work-orders/" + order + "/quality-gates/" + gate(quality, "Patrol", 10) + "/inspections",
                Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("this check is made while it runs")));
        send(operator, "/work-orders/" + order + "/quality-gates/" + gate(quality, "First-off", 10)
                + "/inspections", Map.of()).andExpect(status().isForbidden());
        String firstOff = inspectGate(order, gate(quality, "First-off", 10));
        send(owner, "/work-orders/" + order + "/quality-gates/" + gate(quality, "First-off", 10) + "/inspections",
                Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("First-off already has an inspection under way."));
        decide(firstOff, "20.0", "ACCEPT");
        send(operator, turn + "/start", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gates[?(@.name == 'First-off')].status").value("PASSED"));
        send(operator, turn + "/reports", report("10", "8", "2", "2")).andExpect(status().isOk());
        send(operator, turn + "/complete", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("Patrol is pending.")));

        // A failed patrol puts the batch on hold for rework, raises an NCR, and stops reporting.
        String patrolGate = gate(quality, "Patrol", 10);
        String failed = inspectGate(order, patrolGate);
        decide(failed, "25.0", "REWORK");
        quality = quality(owner, order);
        assertThat(JsonPath.<Boolean>read(quality, "$.data.onHold")).isTrue();
        assertThat(JsonPath.<List<String>>read(quality, "$.data.gates[?(@.id == '" + patrolGate + "')].status"))
                .containsExactly("FAILED");
        assertThat(JsonPath.<List<String>>read(quality, "$.data.ncrs[*].ncrNumber")).hasSize(1);
        assertThat(JsonPath.<List<String>>read(quality(operator, order), "$.data.ncrs")).isEmpty();
        send(operator, turn + "/reports", report("1", "1", "0", "0"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("batch is on hold")));

        // Rework: no more than was marked for it, authorised by someone other than whoever raised it.
        String operationId = operation(order, 0);
        Map<String, Object> rework = new HashMap<>(Map.of("workOrderId", order, "operationId", operationId,
                "inspectionId", failed, "quantity", "3", "reason", "Bore oversize",
                "steps", List.of(Map.of("description", "Regrind bore", "workCenterId", centre))));
        send(supervisor, "/rework-orders", rework)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message")
                        .value("Operation 10 has 2 marked for rework that is not on a rework order yet."));
        rework.put("quantity", "2");
        send(operator, "/rework-orders", rework).andExpect(status().isForbidden());
        String created = body(send(supervisor, "/rework-orders", rework).andExpect(status().isCreated()));
        String id = JsonPath.read(created, "$.data.rework.id");
        assertThat(JsonPath.<String>read(created, "$.data.rework.reworkNumber")).startsWith("RW");
        String path = "/rework-orders/" + id;
        send(operator, path + "/start", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("is open, so it cannot be started")));
        send(supervisor, path + "/approve", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message")
                        .value("You raised this rework order, so someone else must approve it."));
        send(operator, path + "/approve", Map.of()).andExpect(status().isForbidden());
        send(manager, path + "/approve", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rework.status").value("APPROVED"));
        send(operator, path + "/start", Map.of()).andExpect(status().isOk());
        send(operator, path + "/complete", Map.of("reworkedQuantity", "2", "scrappedQuantity", "0"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("Step 1 is not done yet."));
        String step = JsonPath.read(body(as(owner, get(API + path))), "$.data.steps[0].id");
        send(operator, path + "/steps/" + step + "/complete", Map.of("note", "Within size")).andExpect(status().isOk());
        send(operator, path + "/complete", Map.of("reworkedQuantity", "1", "scrappedQuantity", "0"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("Reworked 1 and scrapped 0 make 1, not the 2 on the order."));
        send(operator, path + "/complete", Map.of("reworkedQuantity", "2", "scrappedQuantity", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rework.status").value("AWAITING_INSPECTION"));

        // It closes on the reinspection's decision, which also takes the batch off hold.
        send(supervisor, path + "/close", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("needs a reinspection")));
        String reinspection = JsonPath.read(body(send(owner, path + "/reinspect", Map.of("templateId", ipqc))
                .andExpect(status().isOk())), "$.data.reinspection.id");
        send(owner, path + "/reinspect", Map.of("templateId", ipqc))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("is already under way")));
        send(supervisor, path + "/close", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("has no decision yet")));
        decide(reinspection, "20.0", "ACCEPT");
        send(supervisor, path + "/close", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rework.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.rework.finalDisposition").value("ACCEPT"));
        assertThat(JsonPath.<Boolean>read(quality(owner, order), "$.data.onHold")).isFalse();

        // The failed gate is inspected again, and the operation completes.
        decide(inspectGate(order, patrolGate), "20.0", "ACCEPT");
        send(operator, turn + "/complete", Map.of("reason", "Two went to rework")).andExpect(status().isOk());
        quality = quality(owner, order);
        assertThat(JsonPath.<String>read(quality, "$.data.rejected.rejected")).isEqualTo("2.000000");
        assertThat(JsonPath.<String>read(quality, "$.data.rejected.toRework")).isEqualTo("2.000000");
        assertThat(JsonPath.<String>read(quality, "$.data.rejected.inRework")).isEqualTo("0");
        assertThat(JsonPath.<String>read(quality, "$.data.rejected.reworkedAccepted")).isEqualTo("2.000000");
        assertThat(JsonPath.<List<String>>read(quality, "$.data.unresolved"))
                .containsExactly("Patrol on operation 20 is pending.", "Final is pending.");
        assertThat(JsonPath.<List<String>>read(body(as(owner, get(API + path))), "$.data.history[*].toStatus"))
                .containsExactly("OPEN", "APPROVED", "IN_PROGRESS", "AWAITING_INSPECTION", "CLOSED");

        // Rework records are history.
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                "UPDATE rework_orders SET reason = 'x' WHERE id = ?::uuid", id)))
                .isInstanceOf(DataAccessException.class);
        for (String table : List.of("rework_order_history", "rework_order_steps")) {
            assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                    "DELETE FROM " + table + " WHERE rework_order_id = ?::uuid", id)))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                "DELETE FROM work_order_quality_gates WHERE work_order_id = ?::uuid", order)))
                .isInstanceOf(DataAccessException.class);
        assertThat(asDatabaseSuperuser().queryForList(
                "SELECT action FROM audit_logs WHERE entity_id = ?", String.class, id))
                .contains("REWORK_ORDER_CREATED", "REWORK_ORDER_APPROVED", "REWORK_STEP_COMPLETED",
                        "REWORK_ORDER_REINSPECTION_STARTED", "REWORK_ORDER_CLOSED");
    }

    @Test
    void aQualityHoldStopsTheFloorUntilTheBatchIsReinspected() throws Exception {
        String order = order("5");
        send(manager, "/work-orders/" + order + "/quality-hold", Map.of("reason", "Suspect gauge"))
                .andExpect(status().isUnprocessableContent());
        send(supervisor, "/work-orders/" + order + "/start", Map.of()).andExpect(status().isOk());
        String turn = "/operations/" + operation(order, 0);
        send(operator, turn + "/start", Map.of()).andExpect(status().isOk());
        send(operator, turn + "/reports", report("2", "1", "1", "1")).andExpect(status().isOk());

        send(supervisor, "/work-orders/" + order + "/quality-hold", Map.of("reason", "Suspect gauge"))
                .andExpect(status().isForbidden());
        send(manager, "/work-orders/" + order + "/quality-hold", Map.of("reason", "Suspect gauge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onHold").value(true))
                .andExpect(jsonPath("$.data.batchStatus").value("ON_HOLD"));
        String held = body(as(owner, get(API + turn)));
        assertThat(JsonPath.<String>read(held, "$.data.operation.status")).isEqualTo("PAUSED");
        assertThat(JsonPath.<String>read(held, "$.data.events[-1].lossCategory")).isEqualTo("QUALITY_ISSUE");
        assertThat(JsonPath.<String>read(held, "$.data.batchStatus")).isEqualTo("ON_HOLD");
        send(manager, "/work-orders/" + order + "/quality-hold", Map.of("reason", "Again"))
                .andExpect(status().isUnprocessableContent());
        send(operator, turn + "/resume", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("batch is on hold")));

        // Rework can be raised while the batch is held; cancelled, it no longer counts against the operation.
        Map<String, Object> rework = Map.of("workOrderId", order, "operationId", operation(order, 0),
                "quantity", "1", "reason", "Suspect size", "steps", List.of(Map.of("description", "Re-measure")));
        String first = JsonPath.read(body(send(supervisor, "/rework-orders", rework)
                .andExpect(status().isCreated())), "$.data.rework.id");
        send(supervisor, "/rework-orders", rework).andExpect(status().isUnprocessableContent());
        send(supervisor, "/rework-orders/" + first + "/cancel", Map.of("reason", "Raised twice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rework.status").value("CANCELLED"));
        send(supervisor, "/rework-orders", rework).andExpect(status().isCreated());
        send(supervisor, "/rework-orders", Map.of("workOrderId", order, "quantity", "1", "reason", "x",
                "steps", List.of(Map.of("description", "y"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("Say what is being reworked")));
        assertThat(JsonPath.<List<String>>read(body(as(owner, get(API + "/rework-orders?workOrderId=" + order
                + "&status=OPEN"))), "$.data.items[*].status")).containsExactly("OPEN");

        // An in-process reinspection releases the batch, and the floor goes on.
        String batch = JsonPath.read(quality(owner, order), "$.data.batchId");
        inspectBatch(batch, "IPQC", "20.0", "ACCEPT");
        send(operator, turn + "/resume", Map.of()).andExpect(status().isOk());

        // Other facilities and other organisations see none of it.
        Session limited = addMember(owner, "QUALITY_MANAGER");
        limit(limited, facility(owner));
        as(limited, get(API + "/work-orders/" + order + "/quality")).andExpect(status().isNotFound());
        as(limited, get(API + "/rework-orders/" + first)).andExpect(status().isNotFound());
        assertThat(JsonPath.<List<String>>read(body(as(limited, get(API + "/rework-orders"))), "$.data.items"))
                .isEmpty();
        Session stranger = register();
        as(stranger, get(API + "/work-orders/" + order + "/quality")).andExpect(status().isNotFound());
        as(stranger, get(API + "/rework-orders/" + first)).andExpect(status().isNotFound());
        assertThat(JsonPath.<List<String>>read(body(as(stranger, get(API + "/quality-checkpoints"))), "$.data"))
                .isEmpty();
    }
}
