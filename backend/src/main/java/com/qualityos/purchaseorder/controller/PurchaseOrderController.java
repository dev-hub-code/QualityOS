package com.qualityos.purchaseorder.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.purchaseorder.dto.PurchaseOrderDtos.PurchaseOrderDetail;
import com.qualityos.purchaseorder.dto.PurchaseOrderDtos.PurchaseOrderSummary;
import com.qualityos.purchaseorder.dto.PurchaseOrderDtos.SavePurchaseOrderRequest;
import com.qualityos.purchaseorder.entity.PurchaseOrder;
import com.qualityos.purchaseorder.service.PurchaseOrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/purchase-orders")
@Tag(name = "Purchase orders")
public class PurchaseOrderController {

    private static final Set<String> SORTABLE = Set.of("poNumber", "orderDate", "expectedDate", "status");

    private final PurchaseOrderService orders;

    PurchaseOrderController(PurchaseOrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    @PreAuthorize(Require.PURCHASE_VIEW)
    public ApiResponse<PageResponse<PurchaseOrderSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) PurchaseOrder.Status status,
            @RequestParam(required = false) UUID supplierId) {
        return ApiResponse.ok(orders.list(q, status, supplierId, Pages.of(page, size, sort, SORTABLE, "poNumber")));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Require.PURCHASE_VIEW)
    public ApiResponse<PurchaseOrderDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(orders.get(id));
    }

    @PostMapping
    @PreAuthorize(Require.PURCHASE_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PurchaseOrderDetail> create(@Valid @RequestBody SavePurchaseOrderRequest request) {
        return ApiResponse.ok(orders.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Require.PURCHASE_MANAGE)
    public ApiResponse<PurchaseOrderDetail> update(
            @PathVariable UUID id, @Valid @RequestBody SavePurchaseOrderRequest request) {
        return ApiResponse.ok(orders.update(id, request));
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize(Require.PURCHASE_MANAGE)
    public ApiResponse<PurchaseOrderDetail> issue(@PathVariable UUID id) {
        return ApiResponse.ok(orders.issue(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(Require.PURCHASE_MANAGE)
    public ApiResponse<PurchaseOrderDetail> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(orders.cancel(id));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize(Require.PURCHASE_MANAGE)
    public ApiResponse<PurchaseOrderDetail> close(@PathVariable UUID id) {
        return ApiResponse.ok(orders.close(id));
    }
}
