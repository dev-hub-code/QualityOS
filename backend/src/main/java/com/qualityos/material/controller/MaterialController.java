package com.qualityos.material.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.material.dto.MaterialDtos.CategoryResponse;
import com.qualityos.material.dto.MaterialDtos.MaterialDetail;
import com.qualityos.material.dto.MaterialDtos.MaterialResponse;
import com.qualityos.material.dto.MaterialDtos.SaveCategoryRequest;
import com.qualityos.material.dto.MaterialDtos.SaveMaterialRequest;
import com.qualityos.material.dto.MaterialDtos.SaveSpecificationRequest;
import com.qualityos.material.dto.MaterialDtos.SaveUnitRequest;
import com.qualityos.material.dto.MaterialDtos.SpecificationResponse;
import com.qualityos.material.dto.MaterialDtos.UnitResponse;
import com.qualityos.material.service.MaterialService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Materials")
public class MaterialController {

    private static final Set<String> SORTABLE = Set.of("code", "name", "grade", "createdAt");

    private final MaterialService materials;

    MaterialController(MaterialService materials) {
        this.materials = materials;
    }

    @GetMapping("/materials")
    @PreAuthorize(Require.PRODUCT_VIEW)
    public ApiResponse<PageResponse<MaterialResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q) {
        return ApiResponse.ok(materials.list(q, Pages.of(page, size, sort, SORTABLE, "name")));
    }

    @GetMapping("/materials/{id}")
    @PreAuthorize(Require.PRODUCT_VIEW)
    public ApiResponse<MaterialDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(materials.get(id));
    }

    @PostMapping("/materials")
    @PreAuthorize(Require.PRODUCT_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MaterialResponse> create(@Valid @RequestBody SaveMaterialRequest request) {
        return ApiResponse.ok(materials.create(request));
    }

    @PutMapping("/materials/{id}")
    @PreAuthorize(Require.PRODUCT_MANAGE)
    public ApiResponse<MaterialResponse> update(@PathVariable UUID id, @Valid @RequestBody SaveMaterialRequest request) {
        return ApiResponse.ok(materials.update(id, request));
    }

    @DeleteMapping("/materials/{id}")
    @PreAuthorize(Require.PRODUCT_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID id) {
        materials.archive(id);
    }

    @PostMapping("/materials/{id}/specifications")
    @PreAuthorize(Require.PRODUCT_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SpecificationResponse> addSpecification(
            @PathVariable UUID id, @Valid @RequestBody SaveSpecificationRequest request) {
        return ApiResponse.ok(materials.addSpecification(id, request));
    }

    @DeleteMapping("/materials/{id}/specifications/{specificationId}")
    @PreAuthorize(Require.PRODUCT_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeSpecification(@PathVariable UUID id, @PathVariable UUID specificationId) {
        materials.removeSpecification(id, specificationId);
    }

    @GetMapping("/units")
    @PreAuthorize(Require.PRODUCT_VIEW)
    public ApiResponse<List<UnitResponse>> units() {
        return ApiResponse.ok(materials.listUnits());
    }

    @PostMapping("/units")
    @PreAuthorize(Require.PRODUCT_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UnitResponse> addUnit(@Valid @RequestBody SaveUnitRequest request) {
        return ApiResponse.ok(materials.addUnit(request));
    }

    @GetMapping("/product-categories")
    @PreAuthorize(Require.PRODUCT_VIEW)
    public ApiResponse<List<CategoryResponse>> categories() {
        return ApiResponse.ok(materials.listCategories());
    }

    @PostMapping("/product-categories")
    @PreAuthorize(Require.PRODUCT_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CategoryResponse> addCategory(@Valid @RequestBody SaveCategoryRequest request) {
        return ApiResponse.ok(materials.addCategory(request.name()));
    }
}
