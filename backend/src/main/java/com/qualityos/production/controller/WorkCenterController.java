package com.qualityos.production.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.production.dto.ManufacturingDtos.ChangeMachineStatusRequest;
import com.qualityos.production.dto.ManufacturingDtos.MachineDetail;
import com.qualityos.production.dto.ManufacturingDtos.MachineResponse;
import com.qualityos.production.dto.ManufacturingDtos.SaveMachineRequest;
import com.qualityos.production.dto.ManufacturingDtos.SaveWorkCenterRequest;
import com.qualityos.production.dto.ManufacturingDtos.WorkCenterDetail;
import com.qualityos.production.dto.ManufacturingDtos.WorkCenterResponse;
import com.qualityos.production.entity.Machine;
import com.qualityos.production.service.WorkCenterService;
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
@RequestMapping("/api/v1/manufacturing")
@Tag(name = "Manufacturing")
@PreAuthorize(Require.MANUFACTURING_VIEW)
public class WorkCenterController {

    private static final Set<String> CENTRE_SORT = Set.of("code", "name");
    private static final Set<String> MACHINE_SORT = Set.of("code", "name", "status");

    private final WorkCenterService workCenters;

    WorkCenterController(WorkCenterService workCenters) {
        this.workCenters = workCenters;
    }

    @GetMapping("/work-centers")
    public ApiResponse<PageResponse<WorkCenterResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID facilityId) {
        return ApiResponse.ok(workCenters.list(q, facilityId, Pages.of(page, size, sort, CENTRE_SORT, "code")));
    }

    @PostMapping("/work-centers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Require.MANUFACTURING_MANAGE)
    public ApiResponse<WorkCenterResponse> create(@Valid @RequestBody SaveWorkCenterRequest request) {
        return ApiResponse.ok(workCenters.create(request));
    }

    @GetMapping("/work-centers/{id}")
    public ApiResponse<WorkCenterDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(workCenters.get(id));
    }

    @PutMapping("/work-centers/{id}")
    @PreAuthorize(Require.MANUFACTURING_MANAGE)
    public ApiResponse<WorkCenterResponse> update(@PathVariable UUID id, @Valid @RequestBody SaveWorkCenterRequest request) {
        return ApiResponse.ok(workCenters.update(id, request));
    }

    @DeleteMapping("/work-centers/{id}")
    @PreAuthorize(Require.MANUFACTURING_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID id) {
        workCenters.archive(id);
    }

    @GetMapping("/machines")
    public ApiResponse<PageResponse<MachineResponse>> machines(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID workCenterId,
            @RequestParam(required = false) Machine.Status status) {
        return ApiResponse.ok(workCenters.listMachines(
                q, workCenterId, status, Pages.of(page, size, sort, MACHINE_SORT, "code")));
    }

    @PostMapping("/machines")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Require.MANUFACTURING_MANAGE)
    public ApiResponse<MachineResponse> createMachine(@Valid @RequestBody SaveMachineRequest request) {
        return ApiResponse.ok(workCenters.createMachine(request));
    }

    @GetMapping("/machines/{id}")
    public ApiResponse<MachineDetail> machine(@PathVariable UUID id) {
        return ApiResponse.ok(workCenters.getMachine(id));
    }

    @PutMapping("/machines/{id}")
    @PreAuthorize(Require.MANUFACTURING_MANAGE)
    public ApiResponse<MachineResponse> updateMachine(
            @PathVariable UUID id, @Valid @RequestBody SaveMachineRequest request) {
        return ApiResponse.ok(workCenters.updateMachine(id, request));
    }

    @PostMapping("/machines/{id}/status")
    @PreAuthorize(Require.MANUFACTURING_MANAGE)
    public ApiResponse<MachineResponse> changeStatus(
            @PathVariable UUID id, @Valid @RequestBody ChangeMachineStatusRequest request) {
        return ApiResponse.ok(workCenters.changeStatus(id, request));
    }
}
