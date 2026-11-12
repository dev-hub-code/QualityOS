package com.qualityos.production.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.production.dto.ManufacturingDtos.ApproveRequest;
import com.qualityos.production.dto.ManufacturingDtos.CreateRevisionRequest;
import com.qualityos.production.dto.ManufacturingDtos.RetireRequest;
import com.qualityos.production.dto.ManufacturingDtos.RevisionResponse;
import com.qualityos.production.dto.ManufacturingDtos.RoutingDetail;
import com.qualityos.production.dto.ManufacturingDtos.SaveOperationRequest;
import com.qualityos.production.dto.ManufacturingDtos.SaveParameterRequest;
import com.qualityos.production.dto.ManufacturingDtos.SendBackRequest;
import com.qualityos.production.dto.ManufacturingDtos.UpdateRevisionRequest;
import com.qualityos.production.entity.RevisionedDefinition;
import com.qualityos.production.service.RoutingService;
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
@RequestMapping("/api/v1/manufacturing/routings")
@Tag(name = "Manufacturing")
@PreAuthorize(Require.MANUFACTURING_VIEW)
public class RoutingController {

    private static final Set<String> SORTABLE = Set.of("revision", "status", "createdAt");

    private final RoutingService routings;

    RoutingController(RoutingService routings) {
        this.routings = routings;
    }

    @GetMapping
    public ApiResponse<PageResponse<RevisionResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) RevisionedDefinition.Status status) {
        return ApiResponse.ok(routings.list(q, productId, status,
                Pages.of(page, size, sort == null ? "createdAt,desc" : sort, SORTABLE, "createdAt")));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Require.ROUTING_MANAGE)
    public ApiResponse<RoutingDetail> create(@Valid @RequestBody CreateRevisionRequest request) {
        return ApiResponse.ok(routings.create(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<RoutingDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(routings.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Require.ROUTING_MANAGE)
    public ApiResponse<RoutingDetail> update(@PathVariable UUID id, @Valid @RequestBody UpdateRevisionRequest request) {
        return ApiResponse.ok(routings.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Require.ROUTING_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@PathVariable UUID id) {
        routings.discard(id);
    }

    @PostMapping("/{id}/operations")
    @PreAuthorize(Require.ROUTING_MANAGE)
    public ApiResponse<RoutingDetail> addOperation(
            @PathVariable UUID id, @Valid @RequestBody SaveOperationRequest request) {
        return ApiResponse.ok(routings.addOperation(id, request));
    }

    @PutMapping("/{id}/operations/{operationId}")
    @PreAuthorize(Require.ROUTING_MANAGE)
    public ApiResponse<RoutingDetail> updateOperation(
            @PathVariable UUID id, @PathVariable UUID operationId, @Valid @RequestBody SaveOperationRequest request) {
        return ApiResponse.ok(routings.updateOperation(id, operationId, request));
    }

    @DeleteMapping("/{id}/operations/{operationId}")
    @PreAuthorize(Require.ROUTING_MANAGE)
    public ApiResponse<RoutingDetail> removeOperation(@PathVariable UUID id, @PathVariable UUID operationId) {
        return ApiResponse.ok(routings.removeOperation(id, operationId));
    }

    @PostMapping("/{id}/operations/{operationId}/parameters")
    @PreAuthorize(Require.ROUTING_MANAGE)
    public ApiResponse<RoutingDetail> addParameter(
            @PathVariable UUID id, @PathVariable UUID operationId, @Valid @RequestBody SaveParameterRequest request) {
        return ApiResponse.ok(routings.addParameter(id, operationId, request));
    }

    @DeleteMapping("/{id}/operations/{operationId}/parameters/{parameterId}")
    @PreAuthorize(Require.ROUTING_MANAGE)
    public ApiResponse<RoutingDetail> removeParameter(
            @PathVariable UUID id, @PathVariable UUID operationId, @PathVariable UUID parameterId) {
        return ApiResponse.ok(routings.removeParameter(id, operationId, parameterId));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(Require.ROUTING_MANAGE)
    public ApiResponse<RoutingDetail> submit(@PathVariable UUID id) {
        return ApiResponse.ok(routings.submit(id));
    }

    @PostMapping("/{id}/send-back")
    @PreAuthorize(Require.ROUTING_APPROVE)
    public ApiResponse<RoutingDetail> sendBack(@PathVariable UUID id, @Valid @RequestBody SendBackRequest request) {
        return ApiResponse.ok(routings.sendBack(id, request));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(Require.ROUTING_APPROVE)
    public ApiResponse<RoutingDetail> approve(
            @PathVariable UUID id, @Valid @RequestBody(required = false) ApproveRequest request) {
        return ApiResponse.ok(routings.approve(id, request));
    }

    @PostMapping("/{id}/retire")
    @PreAuthorize(Require.ROUTING_APPROVE)
    public ApiResponse<RoutingDetail> retire(@PathVariable UUID id, @Valid @RequestBody RetireRequest request) {
        return ApiResponse.ok(routings.retire(id, request));
    }
}
