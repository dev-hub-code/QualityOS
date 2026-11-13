package com.qualityos.production.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.production.dto.ProductionAnalyticsDtos.ProductionAnalytics;
import com.qualityos.production.service.ProductionAnalyticsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/manufacturing/analytics")
@Tag(name = "Manufacturing")
@PreAuthorize(Require.PRODUCTION_ANALYTICS_VIEW)
public class ProductionAnalyticsController {

    private final ProductionAnalyticsService analytics;

    ProductionAnalyticsController(ProductionAnalyticsService analytics) {
        this.analytics = analytics;
    }

    /** Production, quality, efficiency, OEE and downtime for the period; cost too for those who may see it. */
    @GetMapping
    public ApiResponse<ProductionAnalytics> analytics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID facilityId,
            Authentication caller) {
        boolean withCost = caller.getAuthorities().stream()
                .anyMatch(held -> Permissions.PRODUCTION_COST_VIEW.equals(held.getAuthority()));
        return ApiResponse.ok(analytics.analytics(from, to, facilityId, withCost));
    }
}
