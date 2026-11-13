package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

class WorkOrderIT extends TraceabilityFixture {

    private static final String API = "/api/v1/manufacturing";

    /** Approves work orders: the owner submits, so someone else must approve. */
    private Session approver;

    @BeforeEach
    void approver() throws Exception {
        approver = addMember(owner, "PRODUCTION_MANAGER");
    }

    private ResultActions send(Session session, String path, Object body) throws Exception {
        return as(session, postJson(API + path, body));
    }

    private ResultActions change(Session session, String path, Object body) throws Exception {
        return as(session, put(API + path).contentType("application/json").content(json.writeValueAsString(body)));
    }

    private String product(String unit) throws Exception {
        return idOf(body(as(owner, postJson("/api/v1/products", Map.of(
                        "code", code(), "name", "Gearbox", "productType", "FINISHED", "uomId", unitId(owner, unit))))
                .andExpect(status().isCreated())));
    }

    private void submitAndApprove(String path, String id) throws Exception {
        send(owner, path + "/" + id + "/submit", Map.of()).andExpect(status().isOk());
        send(manager, path + "/" + id + "/approve", Map.of()).andExpect(status().isOk());
    }

    /** 2 kg of material per piece, with 10% scrap. */
    private void approvedBom(String product, String material) throws Exception {
        String bom = JsonPath.read(body(send(owner, "/boms", Map.of("productId", product))
                .andExpect(status().isCreated())), "$.data.revision.id");
        send(owner, "/boms/" + bom + "/lines", Map.of("materialId", material, "quantity", "2",
                        "uomId", unitId(owner, "KG"), "scrapPercent", "10"))
                .andExpect(status().isOk());
        submitAndApprove("/boms", bom);
    }

    private String workCenter(String facility) throws Exception {
        return idOf(body(send(owner, "/work-centers", Map.of("code", "WC-" + code(), "name", "Assembly",
                        "facilityId", facility))
                .andExpect(status().isCreated())));
    }

    /** One operation: 15 minutes to set up, 1.5 minutes per piece. */
    private void approvedRouting(String product, String centre) throws Exception {
        String routing = JsonPath.read(body(send(owner, "/routings", Map.of("productId", product))
                .andExpect(status().isCreated())), "$.data.revision.id");
        send(owner, "/routings/" + routing + "/operations", Map.of("sequence", 10, "name", "Assemble",
                        "workCenterId", centre, "setupMinutes", "15", "runMinutes", "1.5"))
                .andExpect(status().isOk());
        submitAndApprove("/routings", routing);
    }

    private Map<String, Object> order(String product, String facility, String quantity) {
        return new HashMap<>(Map.of("productId", product, "facilityId", facility, "quantity", quantity,
                "plannedStart", TODAY, "plannedEnd", LocalDate.now().plusDays(3).toString()));
    }

    private String create(Session session, Map<String, Object> request) throws Exception {
        return JsonPath.read(body(send(session, "/work-orders", request).andExpect(status().isCreated())),
                "$.data.workOrder.id");
    }

    private String approved(String product, String facility, String quantity) throws Exception {
        String order = create(owner, order(product, facility, quantity));
        send(owner, "/work-orders/" + order + "/submit", Map.of()).andExpect(status().isOk());
        send(approver, "/work-orders/" + order + "/approve", Map.of()).andExpect(status().isOk());
        return order;
    }

    private String plan(String facility) throws Exception {
        return JsonPath.read(body(send(owner, "/production-plans", Map.of("name", "Week plan", "facilityId", facility,
                        "periodStart", TODAY, "periodEnd", LocalDate.now().plusDays(6).toString()))
                        .andExpect(status().isCreated())),
                "$.data.plan.id");
    }

    @Test
    void aWorkOrderGoesFromDraftToProductionAndKeepsItsHistory() throws Exception {
        String facility = facility(owner);
        String product = product("PCS");
        String material = material(owner, false, "KG");
        approvedBom(product, material);
        approvedRouting(product, workCenter(facility));

        String order = create(owner, order(product, facility, "10"));
        String path = "/work-orders/" + order;
        as(owner, get(API + path))
                .andExpect(jsonPath("$.data.workOrder.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.workOrder.workOrderNumber").value(containsString("WO-")))
                .andExpect(jsonPath("$.data.definition.definitionKind").value("BOM"))
                .andExpect(jsonPath("$.data.definition.problems").isEmpty());

        // Only the next step is open.
        send(owner, path + "/release", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("is draft, so it cannot be released")));
        send(owner, path + "/submit", Map.of()).andExpect(status().isOk());
        change(owner, path, order(product, facility, "12")).andExpect(status().isUnprocessableContent());

        // Whoever submitted cannot also approve.
        send(owner, path + "/approve", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("someone else must approve")));
        send(approver, path + "/approve", Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workOrder.status").value("APPROVED"));

        // Release works out what 10 pieces need: 2 kg each plus 10% scrap.
        String released = body(send(approver, path + "/release", Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workOrder.status").value("RELEASED"))
                .andExpect(jsonPath("$.data.definition.definitionRevision").value(1)));
        assertThat(JsonPath.<String>read(released, "$.data.materials[0].requiredQuantity")).isEqualTo("22.000000");
        assertThat(JsonPath.<String>read(released, "$.data.operations[0].plannedSetupMinutes")).isEqualTo("15.00");
        assertThat(JsonPath.<String>read(released, "$.data.operations[0].plannedRunMinutes")).isEqualTo("15.0000");
        assertThat(JsonPath.<String>read(released, "$.data.operations[0].status")).isEqualTo("PENDING");
        String snapshot = asDatabaseSuperuser().queryForObject(
                "SELECT definition_snapshot::text FROM work_orders WHERE id = ?::uuid", String.class, order);
        assertThat(snapshot).contains("\"productCode\"").contains("\"revision\": 1");

        // Starting opens the production batch that traceability follows; a second start is refused.
        String started = body(send(approver, path + "/start", Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workOrder.status").value("IN_PROGRESS")));
        String batch = JsonPath.read(started, "$.data.workOrder.productionBatchId");
        assertThat(JsonPath.<String>read(started, "$.data.workOrder.productionBatchNumber")).startsWith("PB-");
        as(owner, get("/api/v1/production-batches/" + batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.batch.plannedQuantity").value("10.000000"));
        send(approver, path + "/start", Map.of()).andExpect(status().isUnprocessableContent());

        // Hold needs a reason, and resume returns to where it was. Started production is not cancelled.
        send(approver, path + "/hold", Map.of("reason", " ")).andExpect(status().isBadRequest());
        send(approver, path + "/hold", Map.of("reason", "Tooling check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workOrder.status").value("ON_HOLD"))
                .andExpect(jsonPath("$.data.workOrder.heldFrom").value("IN_PROGRESS"));
        send(approver, path + "/cancel", Map.of("reason", "No longer needed"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("cannot be cancelled")));
        send(approver, path + "/resume", Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workOrder.status").value("IN_PROGRESS"));

        String detail = body(as(owner, get(API + path)));
        assertThat(JsonPath.<List<String>>read(detail, "$.data.history[*].toStatus")).containsExactly(
                "DRAFT", "SUBMITTED", "APPROVED", "RELEASED", "IN_PROGRESS", "ON_HOLD", "IN_PROGRESS");
        assertThat(JsonPath.<String>read(detail, "$.data.history[5].reason")).isEqualTo("Tooling check");
        assertThat(JsonPath.<List<String>>read(body(as(owner, get("/api/v1/audit-logs?size=100"))),
                "$.data.items[*].action")).contains("WORK_ORDER_CREATED", "WORK_ORDER_SUBMITTED",
                "WORK_ORDER_APPROVED", "WORK_ORDER_RELEASED", "WORK_ORDER_STARTED", "WORK_ORDER_HELD",
                "WORK_ORDER_RESUMED", "PRODUCTION_BATCH_STARTED");

        // The database keeps the record whoever asks: no deletion, no rewriting of what was released or its history.
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                        "DELETE FROM work_order_status_history WHERE work_order_id = ?::uuid", order)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                        "UPDATE work_orders SET quantity = 20 WHERE id = ?::uuid", order)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                        "UPDATE work_order_materials SET required_quantity = 1 WHERE work_order_id = ?::uuid", order)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                        "DELETE FROM work_orders WHERE id = ?::uuid", order)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void sendingBackReturnsADraftWithTheReason() throws Exception {
        String facility = facility(owner);
        String order = create(owner, order(product("PCS"), facility, "5"));
        String path = "/work-orders/" + order;
        send(owner, path + "/submit", Map.of()).andExpect(status().isOk());
        send(approver, path + "/send-back", Map.of("note", "Wrong quantity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workOrder.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.workOrder.statusReason").value("Wrong quantity"));
        Map<String, Object> changed = order(JsonPath.read(body(as(owner, get(API + path))), "$.data.workOrder.productId"),
                facility, "6");
        change(owner, path, changed)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workOrder.quantity").value("6"));
        changed.put("plannedEnd", LocalDate.now().minusDays(1).toString());
        change(owner, path, changed)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("The planned end cannot be before the planned start."));
    }

    @Test
    void releaseFollowsTheOrganisationsRules() throws Exception {
        String facility = facility(owner);
        String product = product("PCS");
        String order = approved(product, facility, "4");
        String path = "/work-orders/" + order;

        // A BOM is required by default, and the detail says so before anyone tries.
        as(owner, get(API + path))
                .andExpect(jsonPath("$.data.definition.problems[0]").value(containsString("no approved BOM in effect")));
        send(approver, path + "/release", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("no approved BOM in effect")));

        // Requiring a routing as well; a routing whose work centre is in another facility does not fit.
        change(owner, "/settings", Map.of("manufacturingModel", "DISCRETE", "separateApprover", true,
                        "workOrderRequiresBom", false, "workOrderRequiresRouting", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workOrderRequiresRouting").value(true));
        send(approver, path + "/release", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("no approved routing in effect")));
        approvedRouting(product, workCenter(facility(owner)));
        send(approver, path + "/release", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("in another facility")));

        // Leaving out the work-order rules keeps them.
        change(owner, "/settings", Map.of("manufacturingModel", "DISCRETE", "separateApprover", true))
                .andExpect(jsonPath("$.data.workOrderRequiresBom").value(false))
                .andExpect(jsonPath("$.data.workOrderRequiresRouting").value(true));
        change(owner, "/settings", Map.of("manufacturingModel", "DISCRETE", "separateApprover", true,
                "workOrderRequiresRouting", false)).andExpect(status().isOk());

        // A routing in effect is used even when not required, so its work centres must still fit.
        send(approver, path + "/release", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("in another facility")));
        // With neither required nor in effect, the order is released with nothing to copy.
        String plain = approved(product("PCS"), facility, "4");
        send(approver, "/work-orders/" + plain + "/release", Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.materials").isEmpty())
                .andExpect(jsonPath("$.data.operations").isEmpty());
    }

    @Test
    void cancellingAnApprovedOrderNeedsAnApprover() throws Exception {
        String facility = facility(owner);
        String product = product("PCS");
        Session planner = addMember(owner, "PRODUCTION_PLANNER");
        String draft = create(planner, order(product, facility, "3"));
        send(planner, "/work-orders/" + draft + "/submit", Map.of()).andExpect(status().isOk());
        send(planner, "/work-orders/" + draft + "/approve", Map.of()).andExpect(status().isForbidden());
        send(planner, "/work-orders/" + draft + "/release", Map.of()).andExpect(status().isForbidden());
        send(planner, "/work-orders/" + draft + "/cancel", Map.of("reason", "Duplicate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workOrder.status").value("CANCELLED"));
        send(planner, "/work-orders/" + draft + "/submit", Map.of()).andExpect(status().isUnprocessableContent());

        String committed = approved(product, facility, "3");
        send(planner, "/work-orders/" + committed + "/cancel", Map.of("reason", "Changed my mind"))
                .andExpect(status().isForbidden());
        send(approver, "/work-orders/" + committed + "/hold", Map.of("reason", "Awaiting customer"))
                .andExpect(status().isOk());
        send(approver, "/work-orders/" + committed + "/cancel", Map.of("reason", "Customer withdrew"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workOrder.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.workOrder.heldFrom").isEmpty());

        Session viewer = addMember(owner, "VIEWER");
        as(viewer, get(API + "/work-orders")).andExpect(status().isOk());
        send(viewer, "/work-orders", order(product, facility, "1")).andExpect(status().isForbidden());
        send(manager, "/work-orders", order(product, facility, "1")).andExpect(status().isForbidden());
    }

    @Test
    void workOrdersAreRaisedFromApprovedPlansAndCountAgainstThem() throws Exception {
        String facility = facility(owner);
        String product = product("PCS");
        String plan = plan(facility);
        String path = "/production-plans/" + plan;

        send(owner, path + "/approve", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("Add at least one line before approving the plan."));
        send(owner, path + "/lines", Map.of("productId", product, "quantity", "100",
                        "dueDate", LocalDate.now().plusDays(30).toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("within the plan's period")));
        String line = JsonPath.read(body(send(owner, path + "/lines", Map.of("productId", product,
                        "quantity", "100", "dueDate", LocalDate.now().plusDays(5).toString()))
                .andExpect(status().isCreated())), "$.data.lines[0].id");

        Map<String, Object> fromLine = order(product, facility, "60");
        fromLine.put("planLineId", line);
        send(owner, "/work-orders", fromLine)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("approved plans only")));

        send(approver, path + "/approve", Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.status").value("APPROVED"));
        as(owner, delete(API + path + "/lines/" + line)).andExpect(status().isUnprocessableContent());

        String first = create(owner, fromLine);
        Map<String, Object> elsewhere = new HashMap<>(fromLine);
        elsewhere.put("facilityId", facility(owner));
        send(owner, "/work-orders", elsewhere)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("in the plan's facility")));
        Map<String, Object> otherProduct = new HashMap<>(fromLine);
        otherProduct.put("productId", product("PCS"));
        send(owner, "/work-orders", otherProduct).andExpect(status().isUnprocessableContent());

        // Ordering beyond the plan is allowed and shown.
        fromLine.put("quantity", "50");
        create(owner, fromLine);
        as(owner, get(API + path))
                .andExpect(jsonPath("$.data.lines[0].orderedQuantity").value("110.000000"))
                .andExpect(jsonPath("$.data.lines[0].remainingQuantity").value("0.000000"))
                .andExpect(jsonPath("$.data.lines[0].overOrdered").value(true))
                .andExpect(jsonPath("$.data.lines[0].workOrders.length()").value(2));
        as(owner, get(API + "/work-orders?planId=" + plan))
                .andExpect(jsonPath("$.data.totalItems").value(2))
                .andExpect(jsonPath("$.data.items[0].planNumber").value(containsString("PP-")));

        // A plan with open work orders is neither closed nor cancelled.
        send(approver, path + "/close", Map.of()).andExpect(status().isUnprocessableContent());
        send(approver, path + "/cancel", Map.of("reason", "x")).andExpect(status().isUnprocessableContent());
        send(owner, "/work-orders/" + first + "/cancel", Map.of("reason", "Split differently")).andExpect(status().isOk());
        as(owner, get(API + path)).andExpect(jsonPath("$.data.lines[0].orderedQuantity").value("50.000000"));
        String second = JsonPath.read(body(as(owner, get(API + path))), "$.data.lines[0].workOrders[1].id");
        send(owner, "/work-orders/" + second + "/cancel", Map.of("reason", "Plan dropped")).andExpect(status().isOk());
        send(approver, path + "/close", Map.of("note", "Week done"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.status").value("CLOSED"));
        send(owner, "/work-orders", fromLine).andExpect(status().isUnprocessableContent());
    }

    @Test
    void ordersAndPlansStayInTheirFacilityAndOrganisation() throws Exception {
        String facility = facility(owner);
        String other = facility(owner);
        String product = product("PCS");
        String order = create(owner, order(product, facility, "2"));
        String plan = plan(facility);

        Session limited = addMember(owner, "PRODUCTION_MANAGER");
        limit(limited, other);
        as(limited, get(API + "/work-orders/" + order)).andExpect(status().isNotFound());
        as(limited, get(API + "/production-plans/" + plan)).andExpect(status().isNotFound());
        as(limited, get(API + "/work-orders"))
                .andExpect(jsonPath("$.data.items[?(@.id == '" + order + "')]").isEmpty());
        send(limited, "/work-orders", order(product, facility, "1")).andExpect(status().isNotFound());
        send(limited, "/work-orders", order(product, other, "1")).andExpect(status().isCreated());

        Session stranger = register();
        as(stranger, get(API + "/work-orders/" + order)).andExpect(status().isNotFound());
        as(stranger, get(API + "/production-plans/" + plan)).andExpect(status().isNotFound());
        send(stranger, "/work-orders", order(product, facility, "1")).andExpect(status().isNotFound());
    }
}
