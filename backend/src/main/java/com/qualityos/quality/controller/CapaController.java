package com.qualityos.quality.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.quality.dto.QualityDtos.AddActionRequest;
import com.qualityos.quality.dto.QualityDtos.CapaDetail;
import com.qualityos.quality.dto.QualityDtos.CapaSummary;
import com.qualityos.quality.dto.QualityDtos.CompleteActionRequest;
import com.qualityos.quality.dto.QualityDtos.CreateCapaRequest;
import com.qualityos.quality.dto.QualityDtos.RequiredNoteRequest;
import com.qualityos.quality.dto.QualityDtos.SaveAnalysisRequest;
import com.qualityos.quality.dto.QualityDtos.VerifyRequest;
import com.qualityos.quality.entity.CorrectiveAction;
import com.qualityos.quality.service.CapaService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/capas")
@Tag(name = "Corrective and preventive actions")
public class CapaController {

    private static final Set<String> SORTABLE = Set.of("capaNumber", "status", "targetDate", "createdAt");

    private final CapaService capas;

    CapaController(CapaService capas) {
        this.capas = capas;
    }

    @GetMapping
    @PreAuthorize(Require.CAPA_VIEW)
    public ApiResponse<PageResponse<CapaSummary>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) CorrectiveAction.Status status,
            @RequestParam(required = false) UUID ncrId) {
        return ApiResponse.ok(capas.list(q, status, ncrId, Pages.of(page, size, sort, SORTABLE, "capaNumber")));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Require.CAPA_VIEW)
    public ApiResponse<CapaDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(capas.get(id));
    }

    @PostMapping
    @PreAuthorize(Require.CAPA_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CapaDetail> create(@Valid @RequestBody CreateCapaRequest request) {
        return ApiResponse.ok(capas.create(request));
    }

    @PostMapping("/{id}/start-analysis")
    @PreAuthorize(Require.CAPA_MANAGE)
    public ApiResponse<CapaDetail> startAnalysis(@PathVariable UUID id) {
        return ApiResponse.ok(capas.startAnalysis(id));
    }

    @PutMapping("/{id}/analysis")
    @PreAuthorize(Require.CAPA_MANAGE)
    public ApiResponse<CapaDetail> saveAnalysis(@PathVariable UUID id, @Valid @RequestBody SaveAnalysisRequest request) {
        return ApiResponse.ok(capas.saveAnalysis(id, request));
    }

    @PostMapping("/{id}/finish-analysis")
    @PreAuthorize(Require.CAPA_MANAGE)
    public ApiResponse<CapaDetail> finishAnalysis(@PathVariable UUID id) {
        return ApiResponse.ok(capas.finishAnalysis(id));
    }

    @PostMapping("/{id}/actions")
    @PreAuthorize(Require.CAPA_MANAGE)
    public ApiResponse<CapaDetail> addAction(@PathVariable UUID id, @Valid @RequestBody AddActionRequest request) {
        return ApiResponse.ok(capas.addAction(id, request));
    }

    @PostMapping("/{id}/start-implementation")
    @PreAuthorize(Require.CAPA_MANAGE)
    public ApiResponse<CapaDetail> startImplementation(@PathVariable UUID id) {
        return ApiResponse.ok(capas.startImplementation(id));
    }

    @PostMapping("/{id}/actions/{actionId}/complete")
    @PreAuthorize(Require.CAPA_MANAGE)
    public ApiResponse<CapaDetail> completeAction(
            @PathVariable UUID id, @PathVariable UUID actionId, @Valid @RequestBody CompleteActionRequest request) {
        return ApiResponse.ok(capas.completeAction(id, actionId, request.note()));
    }

    @PostMapping("/{id}/submit-verification")
    @PreAuthorize(Require.CAPA_MANAGE)
    public ApiResponse<CapaDetail> submitForVerification(@PathVariable UUID id) {
        return ApiResponse.ok(capas.submitForVerification(id));
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize(Require.CAPA_APPROVE)
    public ApiResponse<CapaDetail> verify(@PathVariable UUID id, @Valid @RequestBody VerifyRequest request) {
        return ApiResponse.ok(capas.verify(id, request));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize(Require.CAPA_APPROVE)
    public ApiResponse<CapaDetail> close(@PathVariable UUID id, @Valid @RequestBody RequiredNoteRequest request) {
        return ApiResponse.ok(capas.close(id, request.note()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(Require.CAPA_APPROVE)
    public ApiResponse<CapaDetail> cancel(@PathVariable UUID id, @Valid @RequestBody RequiredNoteRequest request) {
        return ApiResponse.ok(capas.cancel(id, request.note()));
    }
}
