package com.qualityos.traceability.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.traceability.dto.TraceabilityDtos.CreateDispatchRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.CustomerResponse;
import com.qualityos.traceability.dto.TraceabilityDtos.DispatchResponse;
import com.qualityos.traceability.dto.TraceabilityDtos.ReverseRequest;
import com.qualityos.traceability.dto.TraceabilityDtos.SaveCustomerRequest;
import com.qualityos.traceability.service.DispatchService;
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
@Tag(name = "Customers and dispatch")
public class DispatchController {

    private static final Set<String> SORTABLE = Set.of("dispatchNumber", "dispatchedOn", "createdAt");

    private final DispatchService dispatches;

    DispatchController(DispatchService dispatches) {
        this.dispatches = dispatches;
    }

    @GetMapping("/customers")
    @PreAuthorize(Require.TRACEABILITY_VIEW)
    public ApiResponse<List<CustomerResponse>> customers() {
        return ApiResponse.ok(dispatches.customers());
    }

    @PostMapping("/customers")
    @PreAuthorize(Require.TRACEABILITY_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CustomerResponse> addCustomer(@Valid @RequestBody SaveCustomerRequest request) {
        return ApiResponse.ok(dispatches.addCustomer(request));
    }

    @DeleteMapping("/customers/{id}")
    @PreAuthorize(Require.TRACEABILITY_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveCustomer(@PathVariable UUID id) {
        dispatches.archiveCustomer(id);
    }

    @GetMapping("/dispatches")
    @PreAuthorize(Require.TRACEABILITY_VIEW)
    public ApiResponse<PageResponse<DispatchResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort) {
        return ApiResponse.ok(dispatches.list(Pages.of(page, size, sort, SORTABLE, "dispatchNumber")));
    }

    @PostMapping("/dispatches")
    @PreAuthorize(Require.TRACEABILITY_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DispatchResponse> create(@Valid @RequestBody CreateDispatchRequest request) {
        return ApiResponse.ok(dispatches.create(request));
    }

    @PostMapping("/dispatches/{id}/reverse")
    @PreAuthorize(Require.TRACEABILITY_MANAGE)
    public ApiResponse<DispatchResponse> reverse(@PathVariable UUID id, @Valid @RequestBody ReverseRequest request) {
        return ApiResponse.ok(dispatches.reverse(id, request.reason()));
    }
}
