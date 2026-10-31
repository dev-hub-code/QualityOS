package com.qualityos.product.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.material.dto.MaterialDtos.SaveSpecificationRequest;
import com.qualityos.material.dto.MaterialDtos.SpecificationResponse;
import com.qualityos.product.dto.ProductDtos.AddMaterialRequest;
import com.qualityos.product.dto.ProductDtos.AddRevisionRequest;
import com.qualityos.product.dto.ProductDtos.BillOfMaterialResponse;
import com.qualityos.product.dto.ProductDtos.ProductDetail;
import com.qualityos.product.dto.ProductDtos.ProductResponse;
import com.qualityos.product.dto.ProductDtos.RevisionResponse;
import com.qualityos.product.dto.ProductDtos.SaveProductRequest;
import com.qualityos.product.entity.Product;
import com.qualityos.product.service.ProductService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/products")
@Tag(name = "Products")
public class ProductController {

    private static final Set<String> SORTABLE = Set.of("code", "name", "createdAt");

    private final ProductService products;

    ProductController(ProductService products) {
        this.products = products;
    }

    @GetMapping
    @PreAuthorize(Require.PRODUCT_VIEW)
    public ApiResponse<PageResponse<ProductResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Product.Type type) {
        return ApiResponse.ok(products.list(q, type, Pages.of(page, size, sort, SORTABLE, "name")));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Require.PRODUCT_VIEW)
    public ApiResponse<ProductDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(products.get(id));
    }

    @PostMapping
    @PreAuthorize(Require.PRODUCT_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductResponse> create(@Valid @RequestBody SaveProductRequest request) {
        return ApiResponse.ok(products.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Require.PRODUCT_MANAGE)
    public ApiResponse<ProductResponse> update(@PathVariable UUID id, @Valid @RequestBody SaveProductRequest request) {
        return ApiResponse.ok(products.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Require.PRODUCT_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID id) {
        products.archive(id);
    }

    @PostMapping("/{id}/revisions")
    @PreAuthorize(Require.PRODUCT_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RevisionResponse> addRevision(
            @PathVariable UUID id, @Valid @RequestBody AddRevisionRequest request) {
        return ApiResponse.ok(products.addRevision(id, request));
    }

    @PostMapping("/{id}/specifications")
    @PreAuthorize(Require.PRODUCT_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SpecificationResponse> addSpecification(
            @PathVariable UUID id, @Valid @RequestBody SaveSpecificationRequest request) {
        return ApiResponse.ok(products.addSpecification(id, request));
    }

    @DeleteMapping("/{id}/specifications/{specificationId}")
    @PreAuthorize(Require.PRODUCT_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeSpecification(@PathVariable UUID id, @PathVariable UUID specificationId) {
        products.removeSpecification(id, specificationId);
    }

    @PostMapping("/{id}/materials")
    @PreAuthorize(Require.PRODUCT_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BillOfMaterialResponse> addMaterial(
            @PathVariable UUID id, @Valid @RequestBody AddMaterialRequest request) {
        return ApiResponse.ok(products.addMaterial(id, request));
    }

    @DeleteMapping("/{id}/materials/{mappingId}")
    @PreAuthorize(Require.PRODUCT_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMaterial(@PathVariable UUID id, @PathVariable UUID mappingId) {
        products.removeMaterial(id, mappingId);
    }
}
