package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.production.service.ProductionReminders;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/** Who hears about approvals, stops on the floor and late production. */
class ProductionNotificationsIT extends TraceabilityFixture {

    private static final String API = "/api/v1/manufacturing";

    @Autowired
    private ProductionReminders reminders;

    private Session supervisor;
    private Session operator;
    private Session planner;
    private String facility;
    private String product;
    private String centre;
    private String machine;
    private String machineCode;
    private String bom;
    private String routing;

    @BeforeEach
    void plant() throws Exception {
        finalInspectionTemplate();
        supervisor = addMember(owner, "PRODUCTION_MANAGER");
        operator = addMember(owner, "PRODUCTION_OPERATOR");
        planner = addMember(owner, "PRODUCTION_PLANNER");
        facility = facility(owner);
        String material = material(owner, false, "KG");
        product = idOf(body(as(owner, postJson("/api/v1/products", Map.of("code", code(), "name", "Shaft",
                        "productType", "FINISHED", "uomId", unitId(owner, "PCS"))))
                .andExpect(status().isCreated())));
        bom = JsonPath.read(body(send(owner, "/boms", Map.of("productId", product))
                .andExpect(status().isCreated())), "$.data.revision.id");
        send(owner, "/boms/" + bom + "/lines", Map.of("materialId", material, "quantity", "1",
                "uomId", unitId(owner, "KG"))).andExpect(status().isOk());
        centre = idOf(body(send(owner, "/work-centers", Map.of("code", "WC-" + code(), "name", "Turning",
                "facilityId", facility)).andExpect(status().isCreated())));
        machineCode = "M-" + code();
        machine = idOf(body(send(owner, "/machines", Map.of("code", machineCode, "name", "Lathe",
                "workCenterId", centre)).andExpect(status().isCreated())));
        routing = JsonPath.read(body(send(owner, "/routings", Map.of("productId", product))
                .andExpect(status().isCreated())), "$.data.revision.id");
        send(owner, "/routings/" + routing + "/operations", Map.of("sequence", 10, "name", "Turn",
                "workCenterId", centre, "setupMinutes", "0", "runMinutes", "60")).andExpect(status().isOk());
    }

    private ResultActions send(Session session, String path, Object body) throws Exception {
        return as(session, postJson(API + path, body));
    }

    private List<String> titles(Session session) throws Exception {
        return JsonPath.read(as(session, get("/api/v1/notifications?size=100"))
                .andReturn().getResponse().getContentAsString(), "$.data.items[*].title");
    }

    private String number(String path) throws Exception {
        return JsonPath.read(body(as(owner, get(API + path))), "$.data.workOrder.workOrderNumber");
    }

    private String order(LocalDate start, LocalDate end) throws Exception {
        return JsonPath.read(body(send(owner, "/work-orders", Map.of("productId", product, "facilityId", facility,
                "quantity", "5", "plannedStart", start.toString(), "plannedEnd", end.toString()))
                .andExpect(status().isCreated())), "$.data.workOrder.id");
    }

    private void approveDefinitions() throws Exception {
        for (String path : List.of("/boms/" + bom, "/routings/" + routing)) {
            send(owner, path + "/submit", Map.of()).andExpect(status().isOk());
            send(manager, path + "/approve", Map.of()).andExpect(status().isOk());
        }
    }

    private int remind() {
        return TenantContext.callAs(owner.tenantId(), null, reminders::remindOverdue);
    }

    @Test
    void approversHearOfSubmissionsAndSubmittersOfSendBacks() throws Exception {
        send(owner, "/boms/" + bom + "/submit", Map.of()).andExpect(status().isOk());
        assertThat(titles(manager)).anyMatch(t -> t.startsWith("BOM ") && t.endsWith("needs approval"));
        send(manager, "/boms/" + bom + "/send-back", Map.of("note", "Add the scrap allowance"))
                .andExpect(status().isOk());
        assertThat(titles(owner)).anyMatch(t -> t.startsWith("BOM ") && t.endsWith("was sent back"));

        send(owner, "/routings/" + routing + "/submit", Map.of()).andExpect(status().isOk());
        assertThat(titles(manager)).anyMatch(t -> t.startsWith("Routing ") && t.endsWith("needs approval"));
        send(manager, "/routings/" + routing + "/approve", Map.of()).andExpect(status().isOk());
        send(owner, "/boms/" + bom + "/submit", Map.of()).andExpect(status().isOk());
        send(manager, "/boms/" + bom + "/approve", Map.of()).andExpect(status().isOk());

        String order = order(LocalDate.now(), LocalDate.now().plusDays(3));
        String number = number("/work-orders/" + order);
        send(owner, "/work-orders/" + order + "/submit", Map.of()).andExpect(status().isOk());
        assertThat(titles(supervisor)).contains(number + " needs approval");
        assertThat(titles(operator)).doesNotContain(number + " needs approval");
        send(supervisor, "/work-orders/" + order + "/send-back", Map.of("note", "Wrong quantity"))
                .andExpect(status().isOk());
        assertThat(titles(owner)).contains(number + " was sent back");
    }

    @Test
    void supervisorsHearWhenTheFloorStops() throws Exception {
        approveDefinitions();
        String order = order(LocalDate.now(), LocalDate.now().plusDays(3));
        String number = number("/work-orders/" + order);
        send(owner, "/work-orders/" + order + "/submit", Map.of()).andExpect(status().isOk());
        send(supervisor, "/work-orders/" + order + "/approve", Map.of()).andExpect(status().isOk());
        send(supervisor, "/work-orders/" + order + "/release", Map.of()).andExpect(status().isOk());
        send(supervisor, "/work-orders/" + order + "/start", Map.of()).andExpect(status().isOk());
        String operation = "/operations/" + JsonPath.<String>read(body(as(owner, get(API + "/work-orders/" + order))),
                "$.data.operations[0].id");

        // Supervisors block; the others hear of it, and the one who blocked does not.
        Session colleague = addMember(owner, "PRODUCTION_MANAGER");
        send(colleague, operation + "/block", Map.of("category", "MATERIAL_SHORTAGE", "reason", "No bar"))
                .andExpect(status().isOk());
        assertThat(titles(supervisor)).contains(number + " operation 10 is blocked");
        assertThat(titles(colleague)).doesNotContain(number + " operation 10 is blocked");
        send(colleague, operation + "/unblock", Map.of()).andExpect(status().isOk());

        send(operator, operation + "/start", Map.of()).andExpect(status().isOk());
        send(operator, operation + "/reports", Map.of("quantity", "2", "goodQuantity", "2", "rejectedQuantity", "0",
                "scrapQuantity", "0", "reworkQuantity", "0")).andExpect(status().isOk());

        send(manager, "/work-orders/" + order + "/quality-hold", Map.of("reason", "Suspect gauge"))
                .andExpect(status().isOk());
        assertThat(titles(supervisor)).contains(number + " is on quality hold");

        send(owner, "/machines/" + machine + "/status", Map.of("status", "BREAKDOWN", "reason", "Spindle seized"))
                .andExpect(status().isOk());
        assertThat(titles(supervisor)).contains(machineCode + " has broken down");
    }

    @Test
    void lateProductionIsChasedOnceAWeek() throws Exception {
        approveDefinitions();
        String order = order(LocalDate.now().minusDays(5), LocalDate.now().minusDays(1));
        String number = number("/work-orders/" + order);
        send(owner, "/work-orders/" + order + "/submit", Map.of()).andExpect(status().isOk());
        send(supervisor, "/work-orders/" + order + "/approve", Map.of()).andExpect(status().isOk());

        String plan = JsonPath.read(body(send(owner, "/production-plans", Map.of("name", "Week plan",
                        "facilityId", facility, "periodStart", LocalDate.now().toString(),
                        "periodEnd", LocalDate.now().plusDays(6).toString())).andExpect(status().isCreated())),
                "$.data.plan.id");
        String line = JsonPath.read(body(send(owner, "/production-plans/" + plan + "/lines", Map.of(
                        "productId", product, "quantity", "100", "dueDate", LocalDate.now().plusDays(2).toString()))
                .andExpect(status().isCreated())), "$.data.lines[0].id");
        send(supervisor, "/production-plans/" + plan + "/approve", Map.of()).andExpect(status().isOk());
        String planNumber = JsonPath.read(body(as(owner, get(API + "/production-plans/" + plan))),
                "$.data.plan.planNumber");

        assertThat(remind()).isEqualTo(1);
        assertThat(titles(supervisor)).contains(number + " is past its planned end");

        asAppRole(owner.tenantId(), sql -> sql.update(
                "UPDATE production_plan_lines SET due_date = current_date - 2 WHERE id = ?::uuid", line));
        assertThat(remind()).isEqualTo(1);
        assertThat(titles(planner)).anyMatch(t -> t.startsWith(planNumber + " ") && t.endsWith("is past its due date"));

        assertThat(remind()).isZero();
        asAppRole(owner.tenantId(), sql -> {
            sql.update("UPDATE work_orders SET overdue_notified_at = now() - interval '8 days' WHERE id = ?::uuid",
                    order);
            sql.update("UPDATE production_plan_lines SET overdue_notified_at = now() - interval '8 days'"
                    + " WHERE id = ?::uuid", line);
        });
        assertThat(remind()).isEqualTo(2);

        // A cancelled order is no longer late, however long ago it was last chased.
        asAppRole(owner.tenantId(), sql -> sql.update(
                "UPDATE work_orders SET overdue_notified_at = NULL WHERE id = ?::uuid", order));
        send(supervisor, "/work-orders/" + order + "/cancel", Map.of("reason", "Not needed")).andExpect(status().isOk());
        assertThat(remind()).isZero();
    }
}
