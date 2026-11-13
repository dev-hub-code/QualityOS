package com.qualityos.production.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.production.dto.WorkOrderDtos.NoteRequest;
import com.qualityos.production.dto.WorkOrderDtos.PlanDetail;
import com.qualityos.production.dto.WorkOrderDtos.PlanResponse;
import com.qualityos.production.dto.WorkOrderDtos.ReasonRequest;
import com.qualityos.production.dto.WorkOrderDtos.SavePlanLineRequest;
import com.qualityos.production.dto.WorkOrderDtos.SavePlanRequest;
import com.qualityos.production.entity.ProductionPlan;
import com.qualityos.production.service.ProductionPlanService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Plans are read by whoever can see work orders, since that is what they turn into. */
@RestController
@RequestMapping("/api/v1/manufacturing/production-plans")
@Tag(name = "Manufacturing")
@PreAuthorize(Require.WORK_ORDER_VIEW)
public class ProductionPlanController {

    private static final Set<String> SORT = Set.of("planNumber", "name", "periodStart", "status", "createdAt");

    private final ProductionPlanService plans;

    ProductionPlanController(ProductionPlanService plans) {
        this.plans = plans;
    }

    @GetMapping
    public ApiResponse<PageResponse<PlanResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ProductionPlan.Status status,
            @RequestParam(required = false) UUID facilityId) {
        return ApiResponse.ok(plans.list(q, status, facilityId, Pages.of(page, size, sort, SORT, "planNumber")));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Require.PRODUCTION_PLAN_MANAGE)
    public ApiResponse<PlanDetail> create(@Valid @RequestBody SavePlanRequest request) {
        return ApiResponse.ok(plans.create(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<PlanDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(plans.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Require.PRODUCTION_PLAN_MANAGE)
    public ApiResponse<PlanDetail> update(@PathVariable UUID id, @Valid @RequestBody SavePlanRequest request) {
        return ApiResponse.ok(plans.update(id, request));
    }

    @PostMapping("/{id}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Require.PRODUCTION_PLAN_MANAGE)
    public ApiResponse<PlanDetail> addLine(@PathVariable UUID id, @Valid @RequestBody SavePlanLineRequest request) {
        return ApiResponse.ok(plans.addLine(id, request));
    }

    @PutMapping("/{id}/lines/{lineId}")
    @PreAuthorize(Require.PRODUCTION_PLAN_MANAGE)
    public ApiResponse<PlanDetail> updateLine(
            @PathVariable UUID id, @PathVariable UUID lineId, @Valid @RequestBody SavePlanLineRequest request) {
        return ApiResponse.ok(plans.updateLine(id, lineId, request));
    }

    @DeleteMapping("/{id}/lines/{lineId}")
    @PreAuthorize(Require.PRODUCTION_PLAN_MANAGE)
    public ApiResponse<PlanDetail> removeLine(@PathVariable UUID id, @PathVariable UUID lineId) {
        return ApiResponse.ok(plans.removeLine(id, lineId));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(Require.PRODUCTION_PLAN_APPROVE)
    public ApiResponse<PlanDetail> approve(@PathVariable UUID id) {
        return ApiResponse.ok(plans.approve(id));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize(Require.PRODUCTION_PLAN_APPROVE)
    public ApiResponse<PlanDetail> close(@PathVariable UUID id, @Valid @RequestBody(required = false) NoteRequest request) {
        return ApiResponse.ok(plans.close(id, request));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(Require.PRODUCTION_PLAN_APPROVE)
    public ApiResponse<PlanDetail> cancel(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return ApiResponse.ok(plans.cancel(id, request));
    }
}
