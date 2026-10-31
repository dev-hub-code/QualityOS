package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class PurchasingIT extends MasterDataFixture {

    @Test
    void anOrderMayBeDraftedForAnUnapprovedSupplierButNotIssued() throws Exception {
        Session owner = register();
        String id = idOf(draftOrder(owner, draftSupplier(owner), material(owner), "100"));

        as(owner, postJson("/api/v1/purchase-orders/" + id + "/issue", Map.of()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void draftsCanChangeButIssuedOrdersCannot() throws Exception {
        Session owner = register();
        String supplier = approvedSupplier(owner);
        String facility = facility(owner);
        String material = material(owner);
        String created = body(as(owner, postJson("/api/v1/purchase-orders",
                orderRequest(supplier, facility, material, "100", null))));
        String id = idOf(created);

        as(owner, put("/api/v1/purchase-orders/" + id).contentType("application/json").content(
                        json.writeValueAsString(orderRequest(supplier, facility, material, "250", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
        as(owner, get("/api/v1/purchase-orders/" + id))
                .andExpect(jsonPath("$.data.items[0].orderedQuantity").value("250.000000"));

        as(owner, postJson("/api/v1/purchase-orders/" + id + "/issue", Map.of())).andExpect(status().isOk());

        as(owner, put("/api/v1/purchase-orders/" + id).contentType("application/json").content(
                        json.writeValueAsString(orderRequest(supplier, facility, material, "1", 2))))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void aStaleEditOfTheItemsIsRefused() throws Exception {
        Session owner = register();
        String supplier = approvedSupplier(owner);
        String facility = facility(owner);
        String material = material(owner);
        String id = idOf(body(as(owner, postJson("/api/v1/purchase-orders",
                orderRequest(supplier, facility, material, "100", null)))));

        as(owner, put("/api/v1/purchase-orders/" + id).contentType("application/json").content(
                        json.writeValueAsString(orderRequest(supplier, facility, material, "200", 0))))
                .andExpect(status().isOk());

        as(owner, put("/api/v1/purchase-orders/" + id).contentType("application/json").content(
                        json.writeValueAsString(orderRequest(supplier, facility, material, "300", 0))))
                .andExpect(status().isConflict());
    }

    @Test
    void severalReceiptsCompleteAnOrder() throws Exception {
        Session owner = register();
        IssuedOrder order = issuedOrder(owner, "100");

        receive(owner, order, "40").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items[0].inspectionStatus").value("PENDING"));
        assertThat(orderStatus(owner, order.id())).isEqualTo("PARTIALLY_RECEIVED");

        receive(owner, order, "60").andExpect(status().isCreated());
        assertThat(orderStatus(owner, order.id())).isEqualTo("RECEIVED");

        as(owner, get("/api/v1/material-receipts").param("purchaseOrderId", order.id()))
                .andExpect(jsonPath("$.data.totalItems").value(2));
    }

    @Test
    void receiptCannotExceedTheRemainingQuantity() throws Exception {
        Session owner = register();
        IssuedOrder order = issuedOrder(owner, "100");
        receive(owner, order, "90").andExpect(status().isCreated());

        receive(owner, order, "10.5")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("Line 1: only 10 remains to be received."));
    }

    @Test
    void refusedReceiptLeavesNoTrace() throws Exception {
        Session owner = register();
        IssuedOrder order = issuedOrder(owner, "100");

        receive(owner, order, "101").andExpect(status().isUnprocessableContent());

        as(owner, get("/api/v1/purchase-orders/" + order.id()))
                .andExpect(jsonPath("$.data.status").value("ISSUED"))
                .andExpect(jsonPath("$.data.items[0].receivedQuantity").value("0.000000"));
        as(owner, get("/api/v1/material-receipts")).andExpect(jsonPath("$.data.totalItems").value(0));
    }

    /** The column holds six decimals; rounding a quantity silently would falsify the record. */
    @Test
    void quantitiesThatCannotBeStoredExactlyAreRefused() throws Exception {
        Session owner = register();
        IssuedOrder order = issuedOrder(owner, "100");

        receive(owner, order, "1.0000001").andExpect(status().isBadRequest());
    }

    @Test
    void aDeliveryMayHoldSeveralSupplierBatchesForOneLine() throws Exception {
        Session owner = register();
        IssuedOrder order = issuedOrder(owner, "100");

        String receipt = body(as(owner, postJson("/api/v1/material-receipts", Map.of(
                        "purchaseOrderId", order.id(),
                        "receivedOn", LocalDate.now().toString(),
                        "items", List.of(
                                Map.of("purchaseOrderItemId", order.itemId(), "supplierBatch", "H1", "quantity", "30"),
                                Map.of("purchaseOrderItemId", order.itemId(), "supplierBatch", "H2", "quantity", "70")))))
                .andExpect(status().isCreated()));

        List<String> lots = JsonPath.read(receipt, "$.data.items[*].internalBatch");
        assertThat(lots).hasSize(2).doesNotHaveDuplicates();
        assertThat(orderStatus(owner, order.id())).isEqualTo("RECEIVED");
    }

    @Test
    void itemsFromAnotherOrderAreRefused() throws Exception {
        Session owner = register();
        IssuedOrder order = issuedOrder(owner, "100");
        IssuedOrder other = issuedOrder(owner, "100");

        receive(owner, new IssuedOrder(order.id(), other.itemId()), "10")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void cannotReceiveAgainstDraftCancelledOrClosedOrders() throws Exception {
        Session owner = register();
        String created = draftOrder(owner, approvedSupplier(owner), material(owner), "100");
        IssuedOrder draft = new IssuedOrder(idOf(created), JsonPath.read(created, "$.data.items[0].id"));
        receive(owner, draft, "1").andExpect(status().isUnprocessableContent());

        IssuedOrder cancelled = issuedOrder(owner, "100");
        as(owner, postJson("/api/v1/purchase-orders/" + cancelled.id() + "/cancel", Map.of()))
                .andExpect(status().isOk());
        receive(owner, cancelled, "1").andExpect(status().isUnprocessableContent());

        IssuedOrder closed = issuedOrder(owner, "100");
        receive(owner, closed, "10").andExpect(status().isCreated());
        as(owner, postJson("/api/v1/purchase-orders/" + closed.id() + "/close", Map.of()))
                .andExpect(status().isOk());
        receive(owner, closed, "1").andExpect(status().isUnprocessableContent());
    }

    @Test
    void orderWithReceiptsCannotBeCancelled() throws Exception {
        Session owner = register();
        IssuedOrder order = issuedOrder(owner, "100");
        receive(owner, order, "10").andExpect(status().isCreated());

        as(owner, postJson("/api/v1/purchase-orders/" + order.id() + "/cancel", Map.of()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void blockedSupplierCannotDeliver() throws Exception {
        Session owner = register();
        String supplier = approvedSupplier(owner);
        String created = draftOrder(owner, supplier, material(owner), "100");
        as(owner, postJson("/api/v1/purchase-orders/" + idOf(created) + "/issue", Map.of())).andExpect(status().isOk());
        as(owner, postJson("/api/v1/suppliers/" + supplier + "/block", Map.of("note", "Audit failed")))
                .andExpect(status().isOk());

        receive(owner, new IssuedOrder(idOf(created), JsonPath.read(created, "$.data.items[0].id")), "1")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void materialThatNeedsNoInspectionSkipsTheQueue() throws Exception {
        Session owner = register();
        IssuedOrder order = issuedOrder(owner, material(owner, false), "10");

        receive(owner, order, "10")
                .andExpect(jsonPath("$.data.items[0].inspectionRequired").value(false))
                .andExpect(jsonPath("$.data.items[0].inspectionStatus").value("NOT_REQUIRED"));
    }

    @Test
    void voidingRestoresTheOrderAndKeepsTheRecord() throws Exception {
        Session owner = register();
        IssuedOrder order = issuedOrder(owner, "100");
        String receipt = idOf(body(receive(owner, order, "100")));
        assertThat(orderStatus(owner, order.id())).isEqualTo("RECEIVED");

        as(owner, postJson("/api/v1/material-receipts/" + receipt + "/void", Map.of("reason", "Wrong quantity")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VOIDED"))
                .andExpect(jsonPath("$.data.items[0].inspectionStatus").value("CANCELLED"));

        assertThat(orderStatus(owner, order.id())).isEqualTo("ISSUED");
        as(owner, postJson("/api/v1/material-receipts/" + receipt + "/void", Map.of("reason", "Again")))
                .andExpect(status().isUnprocessableContent());
        as(owner, get("/api/v1/audit-logs"))
                .andExpect(jsonPath("$.data.items[*].action", hasItem("MATERIAL_RECEIPT_VOIDED")));
    }

    /** Eight simultaneous receipts of 30 against 100: exactly three fit. */
    @Test
    void concurrentReceiptsCannotOverReceive() throws Exception {
        Session owner = register();
        IssuedOrder order = issuedOrder(owner, "100");
        List<Callable<Integer>> attempts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            attempts.add(() -> receive(owner, order, "30").andReturn().getResponse().getStatus());
        }

        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (Future<Integer> result : pool.invokeAll(attempts)) {
                statuses.add(result.get());
            }
        }

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(3);
        assertThat(statuses).filteredOn(s -> s == 422).hasSize(5);
        String detail = body(as(owner, get("/api/v1/purchase-orders/" + order.id())));
        assertThat(new BigDecimal(JsonPath.<String>read(detail, "$.data.items[0].receivedQuantity")))
                .isEqualByComparingTo("90");
    }

    @Test
    void concurrentOrdersGetDistinctNumbers() throws Exception {
        Session owner = register();
        String supplier = approvedSupplier(owner);
        String facility = facility(owner);
        String material = material(owner);
        List<Callable<String>> attempts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            attempts.add(() -> JsonPath.read(body(as(owner, postJson("/api/v1/purchase-orders",
                    orderRequest(supplier, facility, material, "1", null)))), "$.data.poNumber"));
        }

        List<String> numbers = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (Future<String> result : pool.invokeAll(attempts)) {
                numbers.add(result.get());
            }
        }

        assertThat(numbers).hasSize(8).doesNotHaveDuplicates();
    }

    @Test
    void permissionsSeparatePurchasingFromReceiving() throws Exception {
        Session owner = register();
        Session viewer = addMember(owner, "VIEWER");
        IssuedOrder order = issuedOrder(owner, "100");

        as(viewer, get("/api/v1/purchase-orders")).andExpect(status().isOk());
        receive(viewer, order, "1").andExpect(status().isForbidden());
        as(viewer, postJson("/api/v1/purchase-orders/" + order.id() + "/cancel", Map.of()))
                .andExpect(status().isForbidden());
    }
}
