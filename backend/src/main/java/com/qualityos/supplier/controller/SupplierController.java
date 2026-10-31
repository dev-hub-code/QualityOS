package com.qualityos.supplier.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.supplier.dto.SupplierDtos.CategoryResponse;
import com.qualityos.supplier.dto.SupplierDtos.ContactResponse;
import com.qualityos.supplier.dto.SupplierDtos.SaveCategoryRequest;
import com.qualityos.supplier.dto.SupplierDtos.SaveContactRequest;
import com.qualityos.supplier.dto.SupplierDtos.SaveSupplierRequest;
import com.qualityos.supplier.dto.SupplierDtos.StatusNoteRequest;
import com.qualityos.supplier.dto.SupplierDtos.SupplierDetail;
import com.qualityos.supplier.dto.SupplierDtos.SupplierResponse;
import com.qualityos.supplier.entity.Supplier.Status;
import com.qualityos.supplier.service.SupplierService;
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
@RequestMapping("/api/v1/suppliers")
@Tag(name = "Suppliers")
public class SupplierController {

    private static final Set<String> SORTABLE = Set.of("code", "name", "status", "createdAt");

    private final SupplierService suppliers;

    SupplierController(SupplierService suppliers) {
        this.suppliers = suppliers;
    }

    @GetMapping
    @PreAuthorize(Require.SUPPLIER_VIEW)
    public ApiResponse<PageResponse<SupplierResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Status status) {
        return ApiResponse.ok(suppliers.list(q, status, Pages.of(page, size, sort, SORTABLE, "name")));
    }

    @GetMapping("/categories")
    @PreAuthorize(Require.SUPPLIER_VIEW)
    public ApiResponse<List<CategoryResponse>> categories() {
        return ApiResponse.ok(suppliers.listCategories());
    }

    @PostMapping("/categories")
    @PreAuthorize(Require.SUPPLIER_CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CategoryResponse> addCategory(@Valid @RequestBody SaveCategoryRequest request) {
        return ApiResponse.ok(suppliers.addCategory(request.name()));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Require.SUPPLIER_VIEW)
    public ApiResponse<SupplierDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(suppliers.get(id));
    }

    @PostMapping
    @PreAuthorize(Require.SUPPLIER_CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplierResponse> create(@Valid @RequestBody SaveSupplierRequest request) {
        return ApiResponse.ok(suppliers.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Require.SUPPLIER_UPDATE)
    public ApiResponse<SupplierResponse> update(
            @PathVariable UUID id, @Valid @RequestBody SaveSupplierRequest request) {
        return ApiResponse.ok(suppliers.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Require.SUPPLIER_UPDATE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID id) {
        suppliers.archive(id);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(Require.SUPPLIER_UPDATE)
    public ApiResponse<SupplierResponse> submit(@PathVariable UUID id) {
        return ApiResponse.ok(suppliers.changeStatus(id, Status.PENDING_APPROVAL, null));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(Require.SUPPLIER_APPROVE)
    public ApiResponse<SupplierResponse> approve(@PathVariable UUID id) {
        return ApiResponse.ok(suppliers.changeStatus(id, Status.APPROVED, null));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(Require.SUPPLIER_APPROVE)
    public ApiResponse<SupplierResponse> reject(@PathVariable UUID id, @Valid @RequestBody StatusNoteRequest request) {
        return ApiResponse.ok(suppliers.changeStatus(id, Status.REJECTED, request.note()));
    }

    @PostMapping("/{id}/block")
    @PreAuthorize(Require.SUPPLIER_APPROVE)
    public ApiResponse<SupplierResponse> block(@PathVariable UUID id, @Valid @RequestBody StatusNoteRequest request) {
        return ApiResponse.ok(suppliers.changeStatus(id, Status.BLOCKED, request.note()));
    }

    @PostMapping("/{id}/reinstate")
    @PreAuthorize(Require.SUPPLIER_APPROVE)
    public ApiResponse<SupplierResponse> reinstate(@PathVariable UUID id) {
        return ApiResponse.ok(suppliers.changeStatus(id, Status.APPROVED, null));
    }

    @PostMapping("/{id}/contacts")
    @PreAuthorize(Require.SUPPLIER_UPDATE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ContactResponse> addContact(
            @PathVariable UUID id, @Valid @RequestBody SaveContactRequest request) {
        return ApiResponse.ok(suppliers.addContact(id, request));
    }

    @DeleteMapping("/{id}/contacts/{contactId}")
    @PreAuthorize(Require.SUPPLIER_UPDATE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeContact(@PathVariable UUID id, @PathVariable UUID contactId) {
        suppliers.removeContact(id, contactId);
    }
}
