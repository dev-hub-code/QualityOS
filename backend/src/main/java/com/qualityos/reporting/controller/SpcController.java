package com.qualityos.reporting.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.reporting.dto.SpcDtos.ChartChoice;
import com.qualityos.reporting.dto.SpcDtos.SpcChart;
import com.qualityos.reporting.dto.SpcDtos.SpcParameter;
import com.qualityos.reporting.service.SpcService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Control charts and capability, read-only and worked out on request. */
@RestController
@RequestMapping("/api/v1/spc")
@Tag(name = "Analytics and reports")
@PreAuthorize(Require.REPORT_VIEW)
public class SpcController {

    private final SpcService spc;

    SpcController(SpcService spc) {
        this.spc = spc;
    }

    @GetMapping("/parameters")
    public ApiResponse<List<SpcParameter>> parameters(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) UUID facilityId) {
        return ApiResponse.ok(spc.parameters(from, to, facilityId));
    }

    @GetMapping("/chart")
    public ApiResponse<SpcChart> chart(
            @RequestParam UUID templateId,
            @RequestParam String parameterCode,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) UUID facilityId,
            @RequestParam(required = false) UUID materialId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(defaultValue = "AUTO") ChartChoice chart) {
        return ApiResponse.ok(spc.chart(templateId, parameterCode, chart, from, to, facilityId, materialId, productId));
    }
}
