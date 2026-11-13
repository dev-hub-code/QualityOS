package com.qualityos.production.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.security.Permissions.Require;
import com.qualityos.production.dto.DowntimeDtos.DowntimeResponse;
import com.qualityos.production.dto.DowntimeDtos.DowntimeSummary;
import com.qualityos.production.dto.DowntimeDtos.EndDowntimeRequest;
import com.qualityos.production.dto.DowntimeDtos.GroupBy;
import com.qualityos.production.dto.DowntimeDtos.RecordDowntimeRequest;
import com.qualityos.production.dto.DowntimeDtos.UpdateDowntimeRequest;
import com.qualityos.production.entity.OperationEvent.LossCategory;
import com.qualityos.production.service.DowntimeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
@RequestMapping("/api/v1/manufacturing/downtime")
@Tag(name = "Manufacturing")
@PreAuthorize(Require.MANUFACTURING_VIEW)
public class DowntimeController {

    private static final int MAX_PAGE = 100;

    private final DowntimeService downtime;

    DowntimeController(DowntimeService downtime) {
        this.downtime = downtime;
    }

    /** Newest first. {@code from} and {@code to} are days in the organisation's time zone; a stop overlapping counts. */
    @GetMapping
    @SuppressWarnings("java:S107")
    public ApiResponse<PageResponse<DowntimeResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) UUID facilityId,
            @RequestParam(required = false) UUID workCenterId,
            @RequestParam(required = false) UUID machineId,
            @RequestParam(required = false) UUID workOrderId,
            @RequestParam(required = false) LossCategory category,
            @RequestParam(required = false) Boolean open,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(downtime.list(facilityId, workCenterId, machineId, workOrderId, category, open, from, to,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE))));
    }

    @GetMapping("/summary")
    public ApiResponse<DowntimeSummary> summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID facilityId,
            @RequestParam(defaultValue = "MACHINE") GroupBy groupBy) {
        return ApiResponse.ok(downtime.summary(from, to, facilityId, groupBy));
    }

    @GetMapping("/{id}")
    public ApiResponse<DowntimeResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(downtime.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Require.DOWNTIME_MANAGE)
    public ApiResponse<DowntimeResponse> record(@Valid @RequestBody RecordDowntimeRequest request) {
        return ApiResponse.ok(downtime.record(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Require.DOWNTIME_MANAGE)
    public ApiResponse<DowntimeResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateDowntimeRequest request) {
        return ApiResponse.ok(downtime.update(id, request));
    }

    @PostMapping("/{id}/end")
    @PreAuthorize(Require.DOWNTIME_MANAGE)
    public ApiResponse<DowntimeResponse> end(@PathVariable UUID id, @RequestBody(required = false) EndDowntimeRequest request) {
        return ApiResponse.ok(downtime.end(id, request));
    }
}
