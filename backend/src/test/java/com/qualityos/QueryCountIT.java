package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.support.SqlCounter;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * How many SQL statements each list and summary takes as its records grow. A count that grows with the records is a
 * query per record (N+1). See docs/AUDIT_BACKEND_2026-11-17.md.
 */
class QueryCountIT extends TraceabilityFixture {

    private static final String API = "/api/v1/manufacturing";

    @TestConfiguration
    static class Counting {
        @Bean
        static BeanPostProcessor countStatements() {
            return SqlCounter.wrapDataSources();
        }
    }

    private String facility;
    private String product;
    private String lot;

    private String send(String path, Object body) throws Exception {
        return body(as(owner, postJson(API + path, body)).andExpect(status().is2xxSuccessful()));
    }

    private String approvedOrder() throws Exception {
        String order = JsonPath.read(send("/work-orders", Map.of("productId", product, "facilityId", facility,
                "quantity", "2", "plannedStart", TODAY, "plannedEnd", LocalDate.now().plusDays(3).toString())),
                "$.data.workOrder.id");
        send("/work-orders/" + order + "/submit", Map.of());
        send("/work-orders/" + order + "/approve", Map.of());
        return order;
    }

    /** Released, started, given its material, received and closed. */
    private void closedOrder() throws Exception {
        String path = "/work-orders/" + approvedOrder();
        send(path + "/release", Map.of());
        send(path + "/start", Map.of());
        send(path + "/issues", Map.of("lineNumber", 1, "lotId", lot, "quantity", "2"));
        send(path + "/receipt", Map.of("quantity", "2"));
        send(path + "/complete", Map.of());
    }

    private int statements(String path) throws Exception {
        SqlCounter.start();
        try {
            as(owner, get(path)).andExpect(status().isOk());
        } finally {
            return SqlCounter.stop();
        }
    }

    private Map<String, Integer> measure() throws Exception {
        String today = LocalDate.now().toString();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String path : List.of(
                API + "/work-orders",
                API + "/scheduling?facilityId=" + facility + "&from=" + today + "&to=" + LocalDate.now().plusDays(6),
                API + "/analytics?from=" + today + "&to=" + today + "&facilityId=" + facility,
                API + "/costing/summary?from=" + today + "&to=" + today + "&facilityId=" + facility,
                "/api/v1/production-batches",
                "/api/v1/material-receipts")) {
            counts.put(path.replaceAll("\\?.*", ""), statements(path));
        }
        return counts;
    }

    @Test
    void listsAndSummariesTakeTheSameQueriesHoweverManyRecordsTheyShow() throws Exception {
        as(owner, put(API + "/settings").contentType("application/json").content(json.writeValueAsString(Map.of(
                "manufacturingModel", "DISCRETE", "separateApprover", false,
                "completionRequiresFinalInspection", false)))).andExpect(status().isOk());
        facility = facility(owner);
        String material = material(owner, false, "KG");
        product = product();
        String bom = JsonPath.read(send("/boms", Map.of("productId", product)), "$.data.revision.id");
        send("/boms/" + bom + "/lines", Map.of("materialId", material, "quantity", "1", "uomId", unitId(owner, "KG")));
        send("/boms/" + bom + "/submit", Map.of());
        send("/boms/" + bom + "/approve", Map.of());
        lot = idOf(body(as(owner, postJson("/api/v1/purchase-orders", orderRequest(approvedSupplier(owner), facility,
                material, "100", null))).andExpect(status().isCreated())));
        as(owner, postJson("/api/v1/purchase-orders/" + lot + "/issue", Map.of())).andExpect(status().isOk());
        String created = body(as(owner, get("/api/v1/purchase-orders/" + lot)));
        lot = JsonPath.read(body(receive(owner, new IssuedOrder(lot, JsonPath.read(created, "$.data.items[0].id")),
                "100").andExpect(status().isCreated())), "$.data.items[0].id");

        for (int i = 0; i < 2; i++) {
            closedOrder();
            approvedOrder();
        }
        Map<String, Integer> few = measure();
        for (int i = 0; i < 4; i++) {
            closedOrder();
            approvedOrder();
        }
        Map<String, Integer> more = measure();
        few.forEach((path, count) -> System.out.printf("QUERY-COUNT %s %d -> %d%n", path, count, more.get(path)));
        // Three times the orders, the same statements.
        assertThat(more).isEqualTo(few);
    }

    private Map<String, Integer> measureLists() throws Exception {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String path : List.of("/api/v1/inspection-templates?size=100", "/api/v1/users?size=100")) {
            counts.put(path.replaceAll("\\?.*", ""), statements(path));
        }
        return counts;
    }

    /** Templates for a material, and members limited to a facility: each row has its own names and facilities. */
    private void templateAndMember(String material, String facility) throws Exception {
        template("IQC", Map.of("materialId", material));
        limit(addMember(owner, "PLANT_MANAGER"), facility);
    }

    @Test
    void theTemplateAndMemberListsTakeTheSameQueriesHoweverManyRowsTheyShow() throws Exception {
        String material = material(owner, false, "KG");
        String facility = facility(owner);
        for (int i = 0; i < 2; i++) {
            templateAndMember(material, facility);
        }
        Map<String, Integer> few = measureLists();
        for (int i = 0; i < 4; i++) {
            templateAndMember(material, facility);
        }
        Map<String, Integer> more = measureLists();
        few.forEach((path, count) -> System.out.printf("QUERY-COUNT %s %d -> %d%n", path, count, more.get(path)));
        assertThat(more).isEqualTo(few);

        // Loaded together, each member still shows their own facility.
        List<List<String>> limited = JsonPath.read(body(as(owner, get("/api/v1/users?size=100"))),
                "$.data.items[?(@.facilityIds.length() > 0)].facilityIds");
        assertThat(limited).hasSizeGreaterThanOrEqualTo(6).allMatch(ids -> ids.equals(List.of(facility)));
        List<String> appliesTo = JsonPath.read(body(as(owner, get("/api/v1/inspection-templates?size=100"))),
                "$.data.items[*].appliesTo");
        assertThat(appliesTo).hasSizeGreaterThanOrEqualTo(6).doesNotContainNull();
    }
}
