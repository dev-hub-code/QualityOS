package com.qualityos.production.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.production.dto.ManufacturingDtos.ApproveRequest;
import com.qualityos.production.dto.ManufacturingDtos.BomDetail;
import com.qualityos.production.dto.ManufacturingDtos.CreateRevisionRequest;
import com.qualityos.production.dto.ManufacturingDtos.ScaledRecipe;
import com.qualityos.production.dto.ManufacturingDtos.RetireRequest;
import com.qualityos.production.dto.ManufacturingDtos.RevisionResponse;
import com.qualityos.production.dto.ManufacturingDtos.SaveLineRequest;
import com.qualityos.production.dto.ManufacturingDtos.SaveParameterRequest;
import com.qualityos.production.dto.ManufacturingDtos.SendBackRequest;
import com.qualityos.production.dto.ManufacturingDtos.UpdateRevisionRequest;
import com.qualityos.production.entity.Bom;
import com.qualityos.production.entity.RevisionedDefinition;
import com.qualityos.production.service.BomService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Recipes, for process manufacturing. A recipe's base quantity is its batch size. */
@RestController
@RequestMapping("/api/v1/manufacturing/recipes")
@Tag(name = "Manufacturing")
@PreAuthorize(Require.MANUFACTURING_VIEW)
public class RecipeController {

    private static final Bom.Kind KIND = Bom.Kind.RECIPE;
    private static final Set<String> SORTABLE = Set.of("revision", "status", "createdAt");

    private final BomService boms;

    RecipeController(BomService boms) {
        this.boms = boms;
    }

    @GetMapping
    public ApiResponse<PageResponse<RevisionResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) RevisionedDefinition.Status status) {
        return ApiResponse.ok(boms.list(KIND, q, productId, status,
                Pages.of(page, size, sort == null ? "createdAt,desc" : sort, SORTABLE, "createdAt")));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Require.RECIPE_MANAGE)
    public ApiResponse<BomDetail> create(@Valid @RequestBody CreateRevisionRequest request) {
        return ApiResponse.ok(boms.create(KIND, request));
    }

    @GetMapping("/{id}")
    public ApiResponse<BomDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(boms.get(KIND, id));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Require.RECIPE_MANAGE)
    public ApiResponse<BomDetail> update(@PathVariable UUID id, @Valid @RequestBody UpdateRevisionRequest request) {
        return ApiResponse.ok(boms.update(KIND, id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Require.RECIPE_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@PathVariable UUID id) {
        boms.discard(KIND, id);
    }

    @PostMapping("/{id}/lines")
    @PreAuthorize(Require.RECIPE_MANAGE)
    public ApiResponse<BomDetail> addLine(@PathVariable UUID id, @Valid @RequestBody SaveLineRequest request) {
        return ApiResponse.ok(boms.addLine(KIND, id, request));
    }

    @PutMapping("/{id}/lines/{lineId}")
    @PreAuthorize(Require.RECIPE_MANAGE)
    public ApiResponse<BomDetail> updateLine(
            @PathVariable UUID id, @PathVariable UUID lineId, @Valid @RequestBody SaveLineRequest request) {
        return ApiResponse.ok(boms.updateLine(KIND, id, lineId, request));
    }

    @DeleteMapping("/{id}/lines/{lineId}")
    @PreAuthorize(Require.RECIPE_MANAGE)
    public ApiResponse<BomDetail> removeLine(@PathVariable UUID id, @PathVariable UUID lineId) {
        return ApiResponse.ok(boms.removeLine(KIND, id, lineId));
    }

    @PostMapping("/{id}/parameters")
    @PreAuthorize(Require.RECIPE_MANAGE)
    public ApiResponse<BomDetail> addParameter(@PathVariable UUID id, @Valid @RequestBody SaveParameterRequest request) {
        return ApiResponse.ok(boms.addParameter(KIND, id, request));
    }

    @DeleteMapping("/{id}/parameters/{parameterId}")
    @PreAuthorize(Require.RECIPE_MANAGE)
    public ApiResponse<BomDetail> removeParameter(@PathVariable UUID id, @PathVariable UUID parameterId) {
        return ApiResponse.ok(boms.removeParameter(KIND, id, parameterId));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(Require.RECIPE_MANAGE)
    public ApiResponse<BomDetail> submit(@PathVariable UUID id) {
        return ApiResponse.ok(boms.submit(KIND, id));
    }

    @PostMapping("/{id}/send-back")
    @PreAuthorize(Require.RECIPE_APPROVE)
    public ApiResponse<BomDetail> sendBack(@PathVariable UUID id, @Valid @RequestBody SendBackRequest request) {
        return ApiResponse.ok(boms.sendBack(KIND, id, request));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(Require.RECIPE_APPROVE)
    public ApiResponse<BomDetail> approve(@PathVariable UUID id, @Valid @RequestBody(required = false) ApproveRequest request) {
        return ApiResponse.ok(boms.approve(KIND, id, request));
    }

    @PostMapping("/{id}/retire")
    @PreAuthorize(Require.RECIPE_APPROVE)
    public ApiResponse<BomDetail> retire(@PathVariable UUID id, @Valid @RequestBody RetireRequest request) {
        return ApiResponse.ok(boms.retire(KIND, id, request));
    }

    /** The recipe's lines for a batch of {@code batchSize}, in the product's unit. */
    @GetMapping("/{id}/scaled")
    public ApiResponse<ScaledRecipe> scale(@PathVariable UUID id, @RequestParam BigDecimal batchSize) {
        return ApiResponse.ok(boms.scale(id, batchSize));
    }
}
