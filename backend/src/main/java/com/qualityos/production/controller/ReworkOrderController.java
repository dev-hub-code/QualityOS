package com.qualityos.production.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.production.dto.ProductionQualityDtos.CompleteReworkRequest;
import com.qualityos.production.dto.ProductionQualityDtos.CreateReworkRequest;
import com.qualityos.production.dto.ProductionQualityDtos.ReinspectRequest;
import com.qualityos.production.dto.ProductionQualityDtos.ReworkDetail;
import com.qualityos.production.dto.ProductionQualityDtos.ReworkSummary;
import com.qualityos.production.dto.ProductionQualityDtos.StepDoneRequest;
import com.qualityos.production.dto.WorkOrderDtos.NoteRequest;
import com.qualityos.production.dto.WorkOrderDtos.ReasonRequest;
import com.qualityos.production.entity.ReworkOrder;
import com.qualityos.production.service.ReworkService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Rework orders on work order batches. See docs/MANUFACTURING.md. */
@RestController
@RequestMapping("/api/v1/manufacturing/rework-orders")
@Tag(name = "Manufacturing")
@PreAuthorize(Require.WORK_ORDER_VIEW)
public class ReworkOrderController {

    private static final int MAX_PAGE = 100;

    private final ReworkService reworks;

    ReworkOrderController(ReworkService reworks) {
        this.reworks = reworks;
    }

    /** Newest first. */
    @GetMapping
    public ApiResponse<PageResponse<ReworkSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Set<ReworkOrder.Status> status,
            @RequestParam(required = false) UUID workOrderId) {
        return ApiResponse.ok(reworks.list(status, workOrderId, q,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE))));
    }

    @GetMapping("/{id}")
    public ApiResponse<ReworkDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(reworks.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Require.REWORK_MANAGE)
    public ApiResponse<ReworkDetail> create(@Valid @RequestBody CreateReworkRequest request) {
        return ApiResponse.ok(reworks.create(request));
    }

    /** The disposition. A note is needed when someone approves their own, where the organisation allows it. */
    @PostMapping("/{id}/approve")
    @PreAuthorize(Require.REWORK_APPROVE)
    public ApiResponse<ReworkDetail> approve(
            @PathVariable UUID id, @Valid @RequestBody(required = false) NoteRequest request) {
        return ApiResponse.ok(reworks.approve(id, request == null ? null : request.note()));
    }

    @PostMapping("/{id}/start")
    @PreAuthorize(Require.PRODUCTION_EXECUTE)
    public ApiResponse<ReworkDetail> start(@PathVariable UUID id) {
        return ApiResponse.ok(reworks.start(id));
    }

    @PostMapping("/{id}/steps/{stepId}/complete")
    @PreAuthorize(Require.PRODUCTION_EXECUTE)
    public ApiResponse<ReworkDetail> completeStep(
            @PathVariable UUID id, @PathVariable UUID stepId,
            @Valid @RequestBody(required = false) StepDoneRequest request) {
        return ApiResponse.ok(reworks.completeStep(id, stepId, request));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize(Require.PRODUCTION_EXECUTE)
    public ApiResponse<ReworkDetail> complete(
            @PathVariable UUID id, @Valid @RequestBody CompleteReworkRequest request) {
        return ApiResponse.ok(reworks.complete(id, request));
    }

    @PostMapping("/{id}/reinspect")
    @PreAuthorize(Require.INSPECTION_CREATE)
    public ApiResponse<ReworkDetail> reinspect(
            @PathVariable UUID id, @Valid @RequestBody(required = false) ReinspectRequest request) {
        return ApiResponse.ok(reworks.reinspect(id, request));
    }

    /** Takes the reinspection's decision. */
    @PostMapping("/{id}/close")
    @PreAuthorize(Require.REWORK_MANAGE)
    public ApiResponse<ReworkDetail> close(@PathVariable UUID id) {
        return ApiResponse.ok(reworks.close(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(Require.REWORK_MANAGE)
    public ApiResponse<ReworkDetail> cancel(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(reworks.cancel(id, request.reason()));
    }
}
