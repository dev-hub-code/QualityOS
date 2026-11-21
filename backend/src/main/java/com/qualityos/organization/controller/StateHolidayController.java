package com.qualityos.organization.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.organization.dto.StateHolidayDtos.SaveStateHolidayRequest;
import com.qualityos.organization.dto.StateHolidayDtos.StateHolidayResponse;
import com.qualityos.organization.service.StateHolidayService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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
@RequestMapping("/api/v1/state-holidays")
@Tag(name = "Facilities")
public class StateHolidayController {

    private final StateHolidayService holidays;

    StateHolidayController(StateHolidayService holidays) {
        this.holidays = holidays;
    }

    /** {@code region}: a state's GST code, or ALL; every region's when left out. */
    @GetMapping
    @PreAuthorize(Require.FACILITY_VIEW)
    public ApiResponse<List<StateHolidayResponse>> list(@RequestParam(required = false) String region,
            @RequestParam int year) {
        return ApiResponse.ok(holidays.list(region, year));
    }

    @PostMapping
    @PreAuthorize(Require.FACILITY_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<StateHolidayResponse> add(@Valid @RequestBody SaveStateHolidayRequest request) {
        return ApiResponse.ok(holidays.add(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Require.FACILITY_MANAGE)
    public ApiResponse<StateHolidayResponse> update(@PathVariable UUID id,
            @Valid @RequestBody SaveStateHolidayRequest request) {
        return ApiResponse.ok(holidays.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Require.FACILITY_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID id) {
        holidays.remove(id);
    }
}
