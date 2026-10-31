package com.qualityos.reporting.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.reporting.dto.ReportingDtos.Overview;
import com.qualityos.reporting.dto.ReportingDtos.ReportTable;
import com.qualityos.reporting.service.AnalyticsService;
import com.qualityos.reporting.service.ReportExporter;
import com.qualityos.reporting.service.ReportService;
import com.qualityos.reporting.service.ReportType;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Analytics and reports")
@PreAuthorize(Require.REPORT_VIEW)
public class ReportingController {

    private static final String EXCEL = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final AnalyticsService analytics;
    private final ReportService reports;
    private final ReportExporter exporter;

    ReportingController(AnalyticsService analytics, ReportService reports, ReportExporter exporter) {
        this.analytics = analytics;
        this.reports = reports;
        this.exporter = exporter;
    }

    @GetMapping("/analytics/overview")
    public ApiResponse<Overview> overview(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) UUID facilityId) {
        return ApiResponse.ok(analytics.overview(from, to, facilityId));
    }

    @GetMapping("/reports/{slug:[a-z-]+}")
    public ApiResponse<ReportTable> report(
            @PathVariable String slug,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) UUID facilityId,
            Authentication caller) {
        return ApiResponse.ok(reports.view(allowed(slug, caller), from, to, facilityId));
    }

    @GetMapping("/reports/{slug:[a-z-]+}.pdf")
    public ResponseEntity<byte[]> pdf(
            @PathVariable String slug,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) UUID facilityId,
            Authentication caller) {
        ReportType type = allowed(slug, caller);
        return file(slug, "pdf", MediaType.APPLICATION_PDF_VALUE,
                exporter.pdf(reports.export(type, "PDF", from, to, facilityId)));
    }

    @GetMapping("/reports/{slug:[a-z-]+}.xlsx")
    public ResponseEntity<byte[]> excel(
            @PathVariable String slug,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) UUID facilityId,
            Authentication caller) {
        ReportType type = allowed(slug, caller);
        return file(slug, "xlsx", EXCEL, exporter.excel(reports.export(type, "EXCEL", from, to, facilityId)));
    }

    private static ReportType allowed(String slug, Authentication caller) {
        ReportType type = ReportType.of(slug);
        if (caller.getAuthorities().stream().noneMatch(held -> type.permission().equals(held.getAuthority()))) {
            throw new AccessDeniedException("This report needs " + type.permission());
        }
        return type;
    }

    private static ResponseEntity<byte[]> file(String slug, String extension, String mediaType, byte[] content) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mediaType))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(slug + "-report." + extension).build().toString())
                .body(content);
    }
}
