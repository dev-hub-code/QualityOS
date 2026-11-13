package com.qualityos.production.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.production.dto.CostingDtos.CostSummary;
import com.qualityos.production.dto.CostingDtos.WorkOrderCost;
import com.qualityos.production.service.CostingService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/manufacturing/costing")
@Tag(name = "Manufacturing")
@PreAuthorize(Require.PRODUCTION_COST_VIEW)
public class CostingController {

    private final CostingService costing;

    CostingController(CostingService costing) {
        this.costing = costing;
    }

    /** Planned against actual cost of a released work order, and what is missing to cost it fully. */
    @GetMapping("/work-orders/{id}")
    public ApiResponse<WorkOrderCost> workOrder(@PathVariable UUID id) {
        return ApiResponse.ok(costing.ofOrder(id));
    }

    /** Orders closed in the period, planned against actual. */
    @GetMapping("/summary")
    public ApiResponse<CostSummary> summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID facilityId) {
        return ApiResponse.ok(costing.summary(from, to, facilityId));
    }
}
