package com.qualityos.production.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.production.dto.CompletionDtos.CloseRequest;
import com.qualityos.production.dto.CompletionDtos.CompletionResponse;
import com.qualityos.production.dto.CompletionDtos.ProductionRecord;
import com.qualityos.production.dto.CompletionDtos.ReceiptRequest;
import com.qualityos.production.dto.ProductionQualityDtos.GateResponse;
import com.qualityos.production.dto.ProductionQualityDtos.HoldRequest;
import com.qualityos.production.dto.ProductionQualityDtos.QualityResponse;
import com.qualityos.production.dto.WorkOrderDtos.NoteRequest;
import com.qualityos.production.dto.WorkOrderDtos.ReasonRequest;
import com.qualityos.production.dto.WorkOrderDtos.SaveWorkOrderRequest;
import com.qualityos.production.dto.WorkOrderDtos.SendBackWorkOrderRequest;
import com.qualityos.production.dto.WorkOrderDtos.WorkOrderDetail;
import com.qualityos.production.dto.WorkOrderDtos.WorkOrderResponse;
import com.qualityos.production.entity.WorkOrder;
import com.qualityos.production.dto.WorkOrderMaterialDtos.IssueRequest;
import com.qualityos.production.dto.WorkOrderMaterialDtos.MaterialsResponse;
import com.qualityos.production.dto.WorkOrderMaterialDtos.ReserveRequest;
import com.qualityos.production.dto.WorkOrderMaterialDtos.ReturnRequest;
import com.qualityos.production.dto.WorkOrderMaterialDtos.UnreserveRequest;
import com.qualityos.production.service.CompletionService;
import com.qualityos.production.service.QualityGateService;
import com.qualityos.production.service.ShopFloorService;
import com.qualityos.production.service.WorkOrderMaterialService;
import com.qualityos.production.service.WorkOrderService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/manufacturing/work-orders")
@Tag(name = "Manufacturing")
@PreAuthorize(Require.WORK_ORDER_VIEW)
public class WorkOrderController {

    private static final Set<String> SORT = Set.of("workOrderNumber", "plannedStart", "plannedEnd", "status",
            "priority", "createdAt");

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final WorkOrderService workOrders;
    private final WorkOrderMaterialService orderMaterials;
    private final QualityGateService qualityGates;
    private final ShopFloorService shopFloor;
    private final CompletionService completion;

    WorkOrderController(
            WorkOrderService workOrders,
            WorkOrderMaterialService orderMaterials,
            QualityGateService qualityGates,
            ShopFloorService shopFloor,
            CompletionService completion) {
        this.workOrders = workOrders;
        this.orderMaterials = orderMaterials;
        this.qualityGates = qualityGates;
        this.shopFloor = shopFloor;
        this.completion = completion;
    }

    @GetMapping
    public ApiResponse<PageResponse<WorkOrderResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) WorkOrder.Status status,
            @RequestParam(required = false) UUID facilityId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID planId) {
        return ApiResponse.ok(workOrders.list(q, status, facilityId, productId, planId,
                Pages.of(page, size, sort, SORT, "workOrderNumber")));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Require.WORK_ORDER_MANAGE)
    public ApiResponse<WorkOrderDetail> create(@Valid @RequestBody SaveWorkOrderRequest request) {
        return ApiResponse.ok(workOrders.create(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<WorkOrderDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(workOrders.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Require.WORK_ORDER_MANAGE)
    public ApiResponse<WorkOrderDetail> update(@PathVariable UUID id, @Valid @RequestBody SaveWorkOrderRequest request) {
        return ApiResponse.ok(workOrders.update(id, request));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(Require.WORK_ORDER_MANAGE)
    public ApiResponse<WorkOrderDetail> submit(@PathVariable UUID id) {
        return ApiResponse.ok(workOrders.submit(id));
    }

    @PostMapping("/{id}/send-back")
    @PreAuthorize(Require.WORK_ORDER_APPROVE)
    public ApiResponse<WorkOrderDetail> sendBack(
            @PathVariable UUID id, @Valid @RequestBody SendBackWorkOrderRequest request) {
        return ApiResponse.ok(workOrders.sendBack(id, request));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(Require.WORK_ORDER_APPROVE)
    public ApiResponse<WorkOrderDetail> approve(
            @PathVariable UUID id, @Valid @RequestBody(required = false) NoteRequest request) {
        return ApiResponse.ok(workOrders.approve(id, request));
    }

    @PostMapping("/{id}/release")
    @PreAuthorize(Require.WORK_ORDER_RELEASE)
    public ApiResponse<WorkOrderDetail> release(@PathVariable UUID id) {
        return ApiResponse.ok(workOrders.release(id));
    }

    @PostMapping("/{id}/start")
    @PreAuthorize(Require.WORK_ORDER_RELEASE)
    public ApiResponse<WorkOrderDetail> start(@PathVariable UUID id) {
        return ApiResponse.ok(workOrders.start(id));
    }

    @PostMapping("/{id}/hold")
    @PreAuthorize(Require.WORK_ORDER_RELEASE)
    public ApiResponse<WorkOrderDetail> hold(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(workOrders.hold(id, request));
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize(Require.WORK_ORDER_RELEASE)
    public ApiResponse<WorkOrderDetail> resume(@PathVariable UUID id) {
        return ApiResponse.ok(workOrders.resume(id));
    }

    /** Before approval, managing work orders is enough; after it, cancelling needs an approver. */
    @PostMapping("/{id}/cancel")
    @PreAuthorize(Require.WORK_ORDER_CANCEL)
    public ApiResponse<WorkOrderDetail> cancel(
            @PathVariable UUID id, @Valid @RequestBody ReasonRequest request, Authentication caller) {
        boolean canManage = holds(caller, Permissions.WORK_ORDER_MANAGE);
        boolean canApprove = holds(caller, Permissions.WORK_ORDER_APPROVE);
        return ApiResponse.ok(workOrders.cancel(id, request, canManage, canApprove));
    }

    /** Gates, NCRs and CAPAs on the batch (with NCR_VIEW), rework orders, and what still blocks completion. */
    @GetMapping("/{id}/quality")
    public ApiResponse<QualityResponse> quality(@PathVariable UUID id) {
        return ApiResponse.ok(qualityGates.quality(id));
    }

    /** Starts the gate's inspection in the inspection engine, with the template frozen on the gate. */
    @PostMapping("/{id}/quality-gates/{gateId}/inspections")
    @PreAuthorize(Require.INSPECTION_CREATE)
    public ApiResponse<GateResponse> inspectGate(@PathVariable UUID id, @PathVariable UUID gateId) {
        return ApiResponse.ok(qualityGates.inspect(id, gateId));
    }

    /** Puts the batch on hold and pauses what is running. An in-process reinspection releases it. */
    @PostMapping("/{id}/quality-hold")
    @PreAuthorize(Require.INSPECTION_APPROVE)
    public ApiResponse<QualityResponse> qualityHold(@PathVariable UUID id, @Valid @RequestBody HoldRequest request) {
        shopFloor.qualityHold(id, request.reason());
        return ApiResponse.ok(qualityGates.quality(id));
    }

    @GetMapping("/{id}/materials")
    public ApiResponse<MaterialsResponse> materials(@PathVariable UUID id) {
        return ApiResponse.ok(orderMaterials.get(id));
    }

    @PostMapping("/{id}/reservations")
    @PreAuthorize(Require.WORK_ORDER_MATERIAL_RESERVE)
    public ApiResponse<MaterialsResponse> reserve(
            @PathVariable UUID id,
            @Valid @RequestBody ReserveRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key) {
        return ApiResponse.ok(orderMaterials.reserve(id, request, key));
    }

    /** Reserves what every material line still needs from the oldest free lots, as far as they go. */
    @PostMapping("/{id}/reservations/auto")
    @PreAuthorize(Require.WORK_ORDER_MATERIAL_RESERVE)
    public ApiResponse<MaterialsResponse> reserveAvailable(
            @PathVariable UUID id, @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key) {
        return ApiResponse.ok(orderMaterials.reserveAvailable(id, key));
    }

    @PostMapping("/{id}/reservations/{reservationId}/release")
    @PreAuthorize(Require.WORK_ORDER_MATERIAL_RESERVE)
    public ApiResponse<MaterialsResponse> unreserve(
            @PathVariable UUID id,
            @PathVariable UUID reservationId,
            @Valid @RequestBody(required = false) UnreserveRequest request) {
        return ApiResponse.ok(orderMaterials.unreserve(id, reservationId, request == null ? null : request.reason()));
    }

    @PostMapping("/{id}/issues")
    @PreAuthorize(Require.WORK_ORDER_MATERIAL_ISSUE)
    public ApiResponse<MaterialsResponse> issue(
            @PathVariable UUID id,
            @Valid @RequestBody IssueRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key) {
        return ApiResponse.ok(orderMaterials.issue(id, request, key));
    }

    @PostMapping("/{id}/returns")
    @PreAuthorize(Require.WORK_ORDER_MATERIAL_ISSUE)
    public ApiResponse<MaterialsResponse> returnMaterial(
            @PathVariable UUID id,
            @Valid @RequestBody ReturnRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key) {
        return ApiResponse.ok(orderMaterials.returnMaterial(id, request, key));
    }

    /** Totals, material variances, the receipt, and what receiving or closing still waits for. */
    @GetMapping("/{id}/completion")
    public ApiResponse<CompletionResponse> completion(@PathVariable UUID id) {
        return ApiResponse.ok(completion.get(id));
    }

    /** Completes the order's batch with its good output: the finished goods, in the existing inventory. */
    @PostMapping("/{id}/receipt")
    @PreAuthorize(Require.WORK_ORDER_COMPLETE)
    public ApiResponse<CompletionResponse> receive(
            @PathVariable UUID id,
            @Valid @RequestBody ReceiptRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key) {
        return ApiResponse.ok(completion.receive(id, request, key));
    }

    /** Closes the order as completed, or partially completed with a reason. */
    @PostMapping("/{id}/complete")
    @PreAuthorize(Require.WORK_ORDER_COMPLETE)
    public ApiResponse<CompletionResponse> complete(
            @PathVariable UUID id, @Valid @RequestBody(required = false) CloseRequest request) {
        return ApiResponse.ok(completion.close(id, request));
    }

    /** How a batch was made, when a work order made it; null for a batch recorded by hand. */
    @GetMapping("/production-record")
    public ApiResponse<ProductionRecord> productionRecord(@RequestParam UUID batchId) {
        return ApiResponse.ok(completion.record(batchId).orElse(null));
    }

    private static boolean holds(Authentication caller, String permission) {
        return caller.getAuthorities().stream().anyMatch(held -> permission.equals(held.getAuthority()));
    }
}
