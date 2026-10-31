package com.qualityos.traceability.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.traceability.dto.TraceabilityDtos.ApproveCorrectionRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.CorrectionDetail;
import com.qualityos.traceability.dto.TraceabilityDtos.CorrectionNoteRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.CorrectionSummary;
import com.qualityos.traceability.dto.TraceabilityDtos.CreateCorrectionRequest;
import com.qualityos.traceability.entity.MaterialCorrection;
import com.qualityos.traceability.service.MaterialCorrectionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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
@RequestMapping("/api/v1")
@Tag(name = "Material corrections")
public class MaterialCorrectionController {

    private static final Set<String> SORTABLE = Set.of("correctionNumber", "status", "createdAt");

    private final MaterialCorrectionService corrections;

    MaterialCorrectionController(MaterialCorrectionService corrections) {
        this.corrections = corrections;
    }

    @GetMapping("/material-corrections")
    @PreAuthorize(Require.TRACEABILITY_VIEW)
    public ApiResponse<PageResponse<CorrectionSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) MaterialCorrection.Status status) {
        return ApiResponse.ok(corrections.list(q, status, Pages.of(page, size, sort, SORTABLE, "correctionNumber")));
    }

    @GetMapping("/material-corrections/{id}")
    @PreAuthorize(Require.TRACEABILITY_VIEW)
    public ApiResponse<CorrectionDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(corrections.get(id));
    }

    @PostMapping("/material-corrections")
    @PreAuthorize(Require.MATERIAL_CORRECTION_REQUEST)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CorrectionDetail> create(@Valid @RequestBody CreateCorrectionRequest request) {
        return ApiResponse.ok(corrections.create(request));
    }

    @PostMapping("/material-corrections/{id}/submit")
    @PreAuthorize(Require.MATERIAL_CORRECTION_REQUEST)
    public ApiResponse<CorrectionDetail> submit(@PathVariable UUID id) {
        return ApiResponse.ok(corrections.submit(id));
    }

    @PostMapping("/material-corrections/{id}/approve")
    @PreAuthorize(Require.MATERIAL_CORRECTION_APPROVE)
    public ApiResponse<CorrectionDetail> approve(
            @PathVariable UUID id, @Valid @RequestBody ApproveCorrectionRequest request) {
        return ApiResponse.ok(corrections.approve(id, request));
    }

    @PostMapping("/material-corrections/{id}/reject")
    @PreAuthorize(Require.MATERIAL_CORRECTION_APPROVE)
    public ApiResponse<CorrectionDetail> reject(
            @PathVariable UUID id, @Valid @RequestBody CorrectionNoteRequest request) {
        return ApiResponse.ok(corrections.reject(id, request.note()));
    }

    @PostMapping("/material-corrections/{id}/cancel")
    @PreAuthorize(Require.MATERIAL_CORRECTION_REQUEST)
    public ApiResponse<CorrectionDetail> cancel(
            @PathVariable UUID id, @Valid @RequestBody CorrectionNoteRequest request) {
        return ApiResponse.ok(corrections.cancel(id, request.note()));
    }

    /** @param id a received lot or a production batch */
    @GetMapping("/batches/{id}/correction-history")
    @PreAuthorize(Require.TRACEABILITY_VIEW)
    public ApiResponse<List<CorrectionDetail>> history(@PathVariable UUID id) {
        return ApiResponse.ok(corrections.history(id));
    }
}
