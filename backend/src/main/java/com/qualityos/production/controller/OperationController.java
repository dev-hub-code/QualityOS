package com.qualityos.production.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.production.dto.ShopFloorDtos.AssignRequest;
import com.qualityos.production.dto.ShopFloorDtos.BlockRequest;
import com.qualityos.production.dto.ShopFloorDtos.CommentRequest;
import com.qualityos.production.dto.ShopFloorDtos.CompleteRequest;
import com.qualityos.production.dto.ShopFloorDtos.MeasurementRequest;
import com.qualityos.production.dto.ShopFloorDtos.OperationDetail;
import com.qualityos.production.dto.ShopFloorDtos.OperationSummary;
import com.qualityos.production.dto.ShopFloorDtos.ReportRequest;
import com.qualityos.production.dto.ShopFloorDtos.ReverseRequest;
import com.qualityos.production.dto.ShopFloorDtos.SkipRequest;
import com.qualityos.production.dto.ShopFloorDtos.StartRequest;
import com.qualityos.production.dto.ShopFloorDtos.StopRequest;
import com.qualityos.production.entity.WorkOrderOperation;
import com.qualityos.production.service.ShopFloorService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Work order operations on the shop floor. See docs/MANUFACTURING.md. */
@RestController
@RequestMapping("/api/v1/manufacturing/operations")
@Tag(name = "Manufacturing")
@PreAuthorize(Require.WORK_ORDER_VIEW)
public class OperationController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ShopFloorService shopFloor;

    OperationController(ShopFloorService shopFloor) {
        this.shopFloor = shopFloor;
    }

    /** Open operations of orders on the floor, running ones first. */
    @GetMapping
    public ApiResponse<PageResponse<OperationSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Set<WorkOrderOperation.Status> status,
            @RequestParam(required = false) UUID facilityId,
            @RequestParam(required = false) UUID workCenterId,
            @RequestParam(defaultValue = "false") boolean mine) {
        return ApiResponse.ok(shopFloor.list(status, facilityId, workCenterId, mine, q,
                Pages.of(page, size, null, Set.of("sequence"), "sequence")));
    }

    @GetMapping("/{id}")
    public ApiResponse<OperationDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(shopFloor.get(id));
    }

    @PostMapping("/{id}/start")
    @PreAuthorize(Require.PRODUCTION_EXECUTE)
    public ApiResponse<OperationDetail> start(
            @PathVariable UUID id, @Valid @RequestBody(required = false) StartRequest request) {
        return ApiResponse.ok(shopFloor.start(id, request));
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize(Require.PRODUCTION_EXECUTE)
    public ApiResponse<OperationDetail> pause(@PathVariable UUID id, @Valid @RequestBody StopRequest request) {
        return ApiResponse.ok(shopFloor.pause(id, request));
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize(Require.PRODUCTION_EXECUTE)
    public ApiResponse<OperationDetail> resume(@PathVariable UUID id) {
        return ApiResponse.ok(shopFloor.resume(id));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize(Require.PRODUCTION_EXECUTE)
    public ApiResponse<OperationDetail> complete(
            @PathVariable UUID id, @Valid @RequestBody(required = false) CompleteRequest request) {
        return ApiResponse.ok(shopFloor.complete(id, request));
    }

    @PostMapping("/{id}/reports")
    @PreAuthorize(Require.PRODUCTION_EXECUTE)
    public ApiResponse<OperationDetail> report(
            @PathVariable UUID id,
            @Valid @RequestBody ReportRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key) {
        return ApiResponse.ok(shopFloor.report(id, request, key));
    }

    @PostMapping("/{id}/measurements")
    @PreAuthorize(Require.PRODUCTION_EXECUTE)
    public ApiResponse<OperationDetail> measure(
            @PathVariable UUID id,
            @Valid @RequestBody MeasurementRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String key) {
        return ApiResponse.ok(shopFloor.measure(id, request, key));
    }

    @PostMapping("/{id}/comments")
    @PreAuthorize(Require.PRODUCTION_EXECUTE)
    public ApiResponse<OperationDetail> comment(@PathVariable UUID id, @Valid @RequestBody CommentRequest request) {
        return ApiResponse.ok(shopFloor.comment(id, request));
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize(Require.PRODUCTION_SUPERVISE)
    public ApiResponse<OperationDetail> assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest request) {
        return ApiResponse.ok(shopFloor.assign(id, request));
    }

    @PostMapping("/{id}/block")
    @PreAuthorize(Require.PRODUCTION_SUPERVISE)
    public ApiResponse<OperationDetail> block(@PathVariable UUID id, @Valid @RequestBody BlockRequest request) {
        return ApiResponse.ok(shopFloor.block(id, request));
    }

    @PostMapping("/{id}/unblock")
    @PreAuthorize(Require.PRODUCTION_SUPERVISE)
    public ApiResponse<OperationDetail> unblock(@PathVariable UUID id) {
        return ApiResponse.ok(shopFloor.unblock(id));
    }

    @PostMapping("/{id}/skip")
    @PreAuthorize(Require.PRODUCTION_SUPERVISE)
    public ApiResponse<OperationDetail> skip(@PathVariable UUID id, @Valid @RequestBody SkipRequest request) {
        return ApiResponse.ok(shopFloor.skip(id, request));
    }

    @PostMapping("/{id}/reports/{reportId}/reverse")
    @PreAuthorize(Require.PRODUCTION_SUPERVISE)
    public ApiResponse<OperationDetail> reverse(
            @PathVariable UUID id, @PathVariable UUID reportId, @Valid @RequestBody ReverseRequest request) {
        return ApiResponse.ok(shopFloor.reverse(id, reportId, request));
    }
}
