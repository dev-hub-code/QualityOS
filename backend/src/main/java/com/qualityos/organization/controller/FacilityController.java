package com.qualityos.organization.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.organization.dto.FacilityDtos.FacilityDetail;
import com.qualityos.organization.dto.FacilityDtos.FacilityResponse;
import com.qualityos.organization.dto.FacilityDtos.HolidayResponse;
import com.qualityos.organization.dto.FacilityDtos.SaveHolidayRequest;
import com.qualityos.organization.dto.FacilityDtos.SaveFacilityRequest;
import com.qualityos.organization.dto.FacilityDtos.SaveShiftRequest;
import com.qualityos.organization.dto.FacilityDtos.SaveUnitRequest;
import com.qualityos.organization.dto.FacilityDtos.SaveWeeklyOffRequest;
import com.qualityos.organization.dto.FacilityDtos.ShiftResponse;
import com.qualityos.organization.dto.FacilityDtos.UnitResponse;
import com.qualityos.organization.service.FacilityService;
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
@RequestMapping("/api/v1/facilities")
@Tag(name = "Facilities")
public class FacilityController {

    private static final Set<String> SORTABLE = Set.of("code", "name", "city");

    private final FacilityService facilities;

    FacilityController(FacilityService facilities) {
        this.facilities = facilities;
    }

    @GetMapping
    @PreAuthorize(Require.FACILITY_VIEW)
    public ApiResponse<PageResponse<FacilityResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean archived) {
        return ApiResponse.ok(facilities.list(q, archived, Pages.of(page, size, sort, SORTABLE, "name")));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Require.FACILITY_VIEW)
    public ApiResponse<FacilityDetail> get(@PathVariable UUID id) {
        return ApiResponse.ok(facilities.get(id));
    }

    @PostMapping
    @PreAuthorize(Require.FACILITY_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FacilityResponse> create(@Valid @RequestBody SaveFacilityRequest request) {
        return ApiResponse.ok(facilities.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Require.FACILITY_MANAGE)
    public ApiResponse<FacilityResponse> update(
            @PathVariable UUID id, @Valid @RequestBody SaveFacilityRequest request) {
        return ApiResponse.ok(facilities.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Require.FACILITY_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID id) {
        facilities.archive(id);
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize(Require.FACILITY_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable UUID id) {
        facilities.restore(id);
    }

    @PostMapping("/{id}/departments")
    @PreAuthorize(Require.FACILITY_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UnitResponse> addDepartment(@PathVariable UUID id, @Valid @RequestBody SaveUnitRequest request) {
        return ApiResponse.ok(facilities.addDepartment(id, request));
    }

    @DeleteMapping("/{id}/departments/{unitId}")
    @PreAuthorize(Require.FACILITY_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveDepartment(@PathVariable UUID id, @PathVariable UUID unitId) {
        facilities.archiveDepartment(id, unitId);
    }

    @PostMapping("/{id}/production-lines")
    @PreAuthorize(Require.FACILITY_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UnitResponse> addProductionLine(
            @PathVariable UUID id, @Valid @RequestBody SaveUnitRequest request) {
        return ApiResponse.ok(facilities.addProductionLine(id, request));
    }

    @DeleteMapping("/{id}/production-lines/{unitId}")
    @PreAuthorize(Require.FACILITY_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveProductionLine(@PathVariable UUID id, @PathVariable UUID unitId) {
        facilities.archiveProductionLine(id, unitId);
    }

    @PostMapping("/{id}/shifts")
    @PreAuthorize(Require.FACILITY_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ShiftResponse> addShift(@PathVariable UUID id, @Valid @RequestBody SaveShiftRequest request) {
        return ApiResponse.ok(facilities.addShift(id, request));
    }

    @PostMapping("/{id}/holidays")
    @PreAuthorize(Require.FACILITY_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<HolidayResponse> addHoliday(@PathVariable UUID id, @Valid @RequestBody SaveHolidayRequest request) {
        return ApiResponse.ok(facilities.addHoliday(id, request));
    }

    @PutMapping("/{id}/weekly-off")
    @PreAuthorize(Require.FACILITY_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setWeeklyOff(@PathVariable UUID id, @Valid @RequestBody SaveWeeklyOffRequest request) {
        facilities.setWeeklyOff(id, request);
    }

    @PutMapping("/{id}/holidays/{holidayId}")
    @PreAuthorize(Require.FACILITY_MANAGE)
    public ApiResponse<HolidayResponse> updateHoliday(@PathVariable UUID id, @PathVariable UUID holidayId,
            @Valid @RequestBody SaveHolidayRequest request) {
        return ApiResponse.ok(facilities.updateHoliday(id, holidayId, request));
    }

    /** Whether the facility works on a holiday of its state, or of All India. */
    @PutMapping("/{id}/state-holidays/{holidayId}")
    @PreAuthorize(Require.FACILITY_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setStateHolidayWorking(@PathVariable UUID id, @PathVariable UUID holidayId,
            @Valid @RequestBody com.qualityos.organization.dto.StateHolidayDtos.SetWorkingRequest request) {
        facilities.setStateHolidayWorking(id, holidayId, request.working());
    }

    @DeleteMapping("/{id}/holidays/{holidayId}")
    @PreAuthorize(Require.FACILITY_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeHoliday(@PathVariable UUID id, @PathVariable UUID holidayId) {
        facilities.removeHoliday(id, holidayId);
    }

    @DeleteMapping("/{id}/shifts/{shiftId}")
    @PreAuthorize(Require.FACILITY_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveShift(@PathVariable UUID id, @PathVariable UUID shiftId) {
        facilities.archiveShift(id, shiftId);
    }
}
