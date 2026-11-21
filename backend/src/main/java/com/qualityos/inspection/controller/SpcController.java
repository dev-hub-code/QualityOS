package com.qualityos.inspection.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.inspection.dto.SpcDtos.ChartType;
import com.qualityos.inspection.dto.SpcDtos.Characteristic;
import com.qualityos.inspection.dto.SpcDtos.SpcChart;
import com.qualityos.inspection.service.SpcService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Control charts and process capability from inspection measurements. */
@RestController
@RequestMapping("/api/v1/spc")
@Tag(name = "SPC")
public class SpcController {

    private final SpcService spc;

    SpcController(SpcService spc) {
        this.spc = spc;
    }

    @GetMapping("/characteristics")
    @PreAuthorize(Require.INSPECTION_VIEW)
    public ApiResponse<List<Characteristic>> characteristics() {
        return ApiResponse.ok(spc.characteristics());
    }

    /** {@code chartType}: XBAR_R or I_MR; the one the samples suit when left out. */
    @GetMapping("/chart")
    @PreAuthorize(Require.INSPECTION_VIEW)
    public ApiResponse<SpcChart> chart(@RequestParam UUID templateId, @RequestParam String parameterCode,
            @RequestParam(required = false) ChartType chartType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID facilityId) {
        return ApiResponse.ok(spc.chart(templateId, parameterCode, chartType, from, to, facilityId));
    }
}
