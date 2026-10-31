package com.qualityos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds the records that purchasing tests need, through the public API. */
abstract class MasterDataFixture extends AbstractIntegrationTest {

    protected static String code() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    protected String idOf(String body) {
        return JsonPath.read(body, "$.data.id");
    }

    protected String body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    protected String facility(Session session) throws Exception {
        return idOf(body(as(session, postJson("/api/v1/facilities", Map.of("code", code(), "name", "Pune Plant")))
                .andExpect(status().isCreated())));
    }

    protected String draftSupplier(Session session) throws Exception {
        return idOf(body(as(session, postJson("/api/v1/suppliers", Map.of("code", code(), "name", "Bharat Steels")))
                .andExpect(status().isCreated())));
    }

    protected String approvedSupplier(Session session) throws Exception {
        String id = draftSupplier(session);
        as(session, postJson("/api/v1/suppliers/" + id + "/submit", Map.of())).andExpect(status().isOk());
        as(session, postJson("/api/v1/suppliers/" + id + "/approve", Map.of())).andExpect(status().isOk());
        return id;
    }

    protected String unitId(Session session, String unitCode) throws Exception {
        String body = body(as(session, get("/api/v1/units")));
        List<String> ids = JsonPath.read(body, "$.data[?(@.code == '" + unitCode + "')].id");
        return ids.getFirst();
    }

    protected String material(Session session) throws Exception {
        return material(session, true);
    }

    protected String material(Session session, boolean inspectionRequired) throws Exception {
        return idOf(body(as(session, postJson("/api/v1/materials", Map.of(
                        "code", code(), "name", "EN8 Bar", "uomId", unitId(session, "KG"),
                        "inspectionRequired", inspectionRequired)))
                .andExpect(status().isCreated())));
    }

    /** @return the created order's response body */
    protected String draftOrder(Session session, String supplierId, String materialId, String quantity)
            throws Exception {
        return body(as(session, postJson("/api/v1/purchase-orders", orderRequest(
                        supplierId, facility(session), materialId, quantity, null)))
                .andExpect(status().isCreated()));
    }

    protected Map<String, Object> orderRequest(
            String supplierId, String facilityId, String materialId, String quantity, Integer version) {
        Map<String, Object> request = new HashMap<>(Map.of(
                "supplierId", supplierId,
                "facilityId", facilityId,
                "orderDate", LocalDate.now().toString(),
                "items", List.of(Map.of("materialId", materialId, "quantity", quantity))));
        if (version != null) {
            request.put("version", version);
        }
        return request;
    }

    protected record IssuedOrder(String id, String itemId) {
    }

    protected IssuedOrder issuedOrder(Session session, String quantity) throws Exception {
        return issuedOrder(session, material(session), quantity);
    }

    protected IssuedOrder issuedOrder(Session session, String materialId, String quantity) throws Exception {
        String created = draftOrder(session, approvedSupplier(session), materialId, quantity);
        String id = idOf(created);
        as(session, postJson("/api/v1/purchase-orders/" + id + "/issue", Map.of())).andExpect(status().isOk());
        return new IssuedOrder(id, JsonPath.read(created, "$.data.items[0].id"));
    }

    protected org.springframework.test.web.servlet.ResultActions receive(
            Session session, IssuedOrder order, String quantity) throws Exception {
        return as(session, postJson("/api/v1/material-receipts", Map.of(
                "purchaseOrderId", order.id(),
                "receivedOn", LocalDate.now().toString(),
                "items", List.of(Map.of(
                        "purchaseOrderItemId", order.itemId(), "supplierBatch", "HEAT-42", "quantity", quantity)))));
    }

    protected String orderStatus(Session session, String orderId) throws Exception {
        return JsonPath.read(body(as(session, get("/api/v1/purchase-orders/" + orderId))), "$.data.status");
    }
}
