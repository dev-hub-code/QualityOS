package com.qualityos.traceability.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.traceability.dto.TraceabilityDtos.AddSerialsRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.BatchDetail;
import com.qualityos.traceability.dto.TraceabilityDtos.CompleteBatchRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.ConsumeRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.CreateBatchRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.ProductionBatchView;
import com.qualityos.traceability.entity.ProductionBatch;
import com.qualityos.traceability.service.ProductionService;
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
@RequestMapping("/api/v1/production-batches")
@Tag(name = "Production batches")
public class ProductionController {

    private static final Set<String> SORTABLE = Set.of("batchNumber", "status", "createdAt");

    private final ProductionService production;

    ProductionController(ProductionService production) {
        this.production = production;
    }

    @GetMapping
    @PreAuthorize(Require.TRACEABILITY_VIEW)
    public ApiResponse<PageResponse<ProductionBatchView>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) ProductionBatch.Status status,
            @RequestParam(required = false) UUID productId) {
        return ApiResponse.ok(production.list(q, status, productId, Pages.of(page, size, sort, SORTABLE, "batchNumber")));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Require.TRACEABILITY_VIEW)
    public ApiResponse<BatchDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(production.detail(id));
    }

    @PostMapping
    @PreAuthorize(Require.TRACEABILITY_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BatchDetail> create(@Valid @RequestBody CreateBatchRequest request) {
        return ApiResponse.ok(production.create(request));
    }

    @PostMapping("/{id}/consume")
    @PreAuthorize(Require.TRACEABILITY_MANAGE)
    public ApiResponse<BatchDetail> consume(@PathVariable UUID id, @Valid @RequestBody ConsumeRequest request) {
        return ApiResponse.ok(production.consume(id, request));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize(Require.TRACEABILITY_MANAGE)
    public ApiResponse<BatchDetail> complete(@PathVariable UUID id, @Valid @RequestBody CompleteBatchRequest request) {
        return ApiResponse.ok(production.complete(id, request));
    }

    @PostMapping("/{id}/serials")
    @PreAuthorize(Require.TRACEABILITY_MANAGE)
    public ApiResponse<BatchDetail> addSerials(@PathVariable UUID id, @Valid @RequestBody AddSerialsRequest request) {
        return ApiResponse.ok(production.addSerials(id, request));
    }
}
