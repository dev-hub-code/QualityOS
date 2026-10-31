package com.qualityos.quality.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.quality.dto.QualityDtos.AssignRequest;
import com.qualityos.quality.dto.QualityDtos.Assignee;
import com.qualityos.quality.dto.QualityDtos.ContainmentRequest;
import com.qualityos.quality.dto.QualityDtos.CreateNcrRequest;
import com.qualityos.quality.dto.QualityDtos.DefectCategoryResponse;
import com.qualityos.quality.dto.QualityDtos.InvestigationRequest;
import com.qualityos.quality.dto.QualityDtos.NcrDetail;
import com.qualityos.quality.dto.QualityDtos.NcrSummary;
import com.qualityos.quality.dto.QualityDtos.NoteRequest;
import com.qualityos.quality.dto.QualityDtos.RequiredNoteRequest;
import com.qualityos.quality.dto.QualityDtos.SaveDefectCategoryRequest;
import com.qualityos.quality.entity.NonConformance;
import com.qualityos.quality.service.NcrService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Non-conformances")
public class NcrController {

    private static final Set<String> SORTABLE = Set.of("ncrNumber", "status", "severity", "createdAt");

    private final NcrService ncrs;

    NcrController(NcrService ncrs) {
        this.ncrs = ncrs;
    }

    @GetMapping("/quality/assignees")
    @PreAuthorize(Require.NCR_VIEW + " or " + Require.CAPA_VIEW)
    public ApiResponse<List<Assignee>> assignees() {
        return ApiResponse.ok(ncrs.assignees());
    }

    @GetMapping("/defect-categories")
    @PreAuthorize(Require.NCR_VIEW)
    public ApiResponse<List<DefectCategoryResponse>> categories() {
        return ApiResponse.ok(ncrs.categories());
    }

    @PostMapping("/defect-categories")
    @PreAuthorize(Require.NCR_ASSIGN)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DefectCategoryResponse> addCategory(@Valid @RequestBody SaveDefectCategoryRequest request) {
        return ApiResponse.ok(ncrs.addCategory(request));
    }

    @DeleteMapping("/defect-categories/{id}")
    @PreAuthorize(Require.NCR_ASSIGN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveCategory(@PathVariable UUID id) {
        ncrs.archiveCategory(id);
    }

    @GetMapping("/ncrs")
    @PreAuthorize(Require.NCR_VIEW)
    public ApiResponse<PageResponse<NcrSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) NonConformance.Status status,
            @RequestParam(required = false) NonConformance.Severity severity,
            @RequestParam(required = false) UUID inspectionId) {
        return ApiResponse.ok(ncrs.list(
                q, status, severity, inspectionId, Pages.of(page, size, sort, SORTABLE, "ncrNumber")));
    }

    @GetMapping("/ncrs/{id}")
    @PreAuthorize(Require.NCR_VIEW)
    public ApiResponse<NcrDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(ncrs.get(id));
    }

    @PostMapping("/ncrs")
    @PreAuthorize(Require.NCR_CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<NcrDetail> create(@Valid @RequestBody CreateNcrRequest request) {
        return ApiResponse.ok(ncrs.create(request));
    }

    @PostMapping("/ncrs/{id}/assign")
    @PreAuthorize(Require.NCR_ASSIGN)
    public ApiResponse<NcrDetail> assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest request) {
        return ApiResponse.ok(ncrs.assign(id, request.assigneeId()));
    }

    @PostMapping("/ncrs/{id}/containment")
    @PreAuthorize(Require.NCR_ASSIGN)
    public ApiResponse<NcrDetail> containment(@PathVariable UUID id, @Valid @RequestBody ContainmentRequest request) {
        return ApiResponse.ok(ncrs.recordContainment(id, request.containmentAction()));
    }

    @PostMapping("/ncrs/{id}/investigation")
    @PreAuthorize(Require.NCR_ASSIGN)
    public ApiResponse<NcrDetail> investigation(@PathVariable UUID id, @Valid @RequestBody InvestigationRequest request) {
        return ApiResponse.ok(ncrs.recordInvestigation(id, request));
    }

    @PostMapping("/ncrs/{id}/request-verification")
    @PreAuthorize(Require.NCR_ASSIGN)
    public ApiResponse<NcrDetail> requestVerification(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return ApiResponse.ok(ncrs.requestVerification(id, request.note()));
    }

    @PostMapping("/ncrs/{id}/send-back")
    @PreAuthorize(Require.NCR_CLOSE)
    public ApiResponse<NcrDetail> sendBack(@PathVariable UUID id, @Valid @RequestBody RequiredNoteRequest request) {
        return ApiResponse.ok(ncrs.sendBack(id, request.note()));
    }

    @PostMapping("/ncrs/{id}/close")
    @PreAuthorize(Require.NCR_CLOSE)
    public ApiResponse<NcrDetail> close(@PathVariable UUID id, @Valid @RequestBody RequiredNoteRequest request) {
        return ApiResponse.ok(ncrs.close(id, request.note()));
    }

    @PostMapping("/ncrs/{id}/reject")
    @PreAuthorize(Require.NCR_CLOSE)
    public ApiResponse<NcrDetail> reject(@PathVariable UUID id, @Valid @RequestBody RequiredNoteRequest request) {
        return ApiResponse.ok(ncrs.reject(id, request.note()));
    }

    @PostMapping("/ncrs/{id}/cancel")
    @PreAuthorize(Require.NCR_ASSIGN)
    public ApiResponse<NcrDetail> cancel(@PathVariable UUID id, @Valid @RequestBody RequiredNoteRequest request) {
        return ApiResponse.ok(ncrs.cancel(id, request.note()));
    }
}
