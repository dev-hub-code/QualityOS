package com.qualityos.inspection.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.inspection.dto.InspectionDtos.ApproveRequest;
import com.qualityos.inspection.dto.InspectionDtos.CreateInspectionRequest;
import com.qualityos.inspection.dto.InspectionDtos.DispositionRequest;
import com.qualityos.inspection.dto.InspectionDtos.ExecuteRequest;
import com.qualityos.inspection.dto.InspectionDtos.InspectionDetail;
import com.qualityos.inspection.dto.InspectionDtos.InspectionSummary;
import com.qualityos.inspection.dto.InspectionDtos.SendBackRequest;
import com.qualityos.inspection.entity.Inspection;
import com.qualityos.inspection.service.InspectionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
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

@RestController
@RequestMapping("/api/v1/inspections")
@Tag(name = "Inspections")
public class InspectionController {

    private static final Set<String> SORTABLE = Set.of("inspectionNumber", "status", "createdAt");

    private final InspectionService inspections;

    InspectionController(InspectionService inspections) {
        this.inspections = inspections;
    }

    @GetMapping
    @PreAuthorize(Require.INSPECTION_VIEW)
    public ApiResponse<PageResponse<InspectionSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Inspection.Status status,
            @RequestParam(required = false) UUID supplierId) {
        return ApiResponse.ok(inspections.list(
                q, status, supplierId, Pages.of(page, size, sort, SORTABLE, "inspectionNumber")));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Require.INSPECTION_VIEW)
    public ApiResponse<InspectionDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(inspections.get(id));
    }

    @PostMapping
    @PreAuthorize(Require.INSPECTION_CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<InspectionDetail> create(@Valid @RequestBody CreateInspectionRequest request) {
        return ApiResponse.ok(inspections.create(request));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(Require.INSPECTION_CREATE)
    public ApiResponse<InspectionDetail> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(inspections.cancel(id));
    }

    @PostMapping("/{id}/execute")
    @PreAuthorize(Require.INSPECTION_EXECUTE)
    public ApiResponse<InspectionDetail> execute(@PathVariable UUID id, @Valid @RequestBody ExecuteRequest request) {
        return ApiResponse.ok(inspections.execute(id, request));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(Require.INSPECTION_EXECUTE)
    public ApiResponse<InspectionDetail> submit(@PathVariable UUID id) {
        return ApiResponse.ok(inspections.submit(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(Require.INSPECTION_APPROVE)
    public ApiResponse<InspectionDetail> approve(@PathVariable UUID id, @Valid @RequestBody ApproveRequest request) {
        return ApiResponse.ok(inspections.approve(id, request));
    }

    @PostMapping("/{id}/send-back")
    @PreAuthorize(Require.INSPECTION_APPROVE)
    public ApiResponse<InspectionDetail> sendBack(@PathVariable UUID id, @Valid @RequestBody SendBackRequest request) {
        return ApiResponse.ok(inspections.sendBack(id, request.note()));
    }

    @PostMapping("/{id}/disposition")
    @PreAuthorize(Require.DISPOSITION_RECORD)
    public ApiResponse<InspectionDetail> disposition(
            @PathVariable UUID id, @Valid @RequestBody DispositionRequest request) {
        return ApiResponse.ok(inspections.dispose(id, request));
    }
}
