package com.qualityos.calibration.controller;

import com.qualityos.calibration.dto.CalibrationDtos.CalibrationResponse;
import com.qualityos.calibration.dto.CalibrationDtos.ChangeStatusRequest;
import com.qualityos.calibration.dto.CalibrationDtos.InstrumentResponse;
import com.qualityos.calibration.dto.CalibrationDtos.RecordCalibrationRequest;
import com.qualityos.calibration.dto.CalibrationDtos.SaveInstrumentRequest;
import com.qualityos.calibration.dto.CalibrationDtos.UsageResponse;
import com.qualityos.calibration.entity.Instrument;
import com.qualityos.calibration.service.InstrumentService;
import com.qualityos.calibration.service.QrCodes;
import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.response.Pages;
import com.qualityos.common.security.Permissions.Require;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/instruments")
@Tag(name = "Calibration")
@PreAuthorize(Require.CALIBRATION_VIEW)
public class InstrumentController {

    private static final Set<String> SORTABLE = Set.of("instrumentCode", "name", "nextDueOn", "status");

    private final InstrumentService instruments;
    private final String appUrl;

    InstrumentController(InstrumentService instruments, @Value("${qualityos.app-url}") String appUrl) {
        this.instruments = instruments;
        this.appUrl = appUrl;
    }

    @GetMapping
    public ApiResponse<PageResponse<InstrumentResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Instrument.Status status,
            @RequestParam(required = false) UUID facilityId) {
        return ApiResponse.ok(instruments.list(
                q, status, facilityId, false, Pages.of(page, size, sort, SORTABLE, "nextDueOn")));
    }

    /** For choosing an instrument during an inspection, by people who need not see the calibration register. */
    @GetMapping("/usable")
    @PreAuthorize(Require.INSPECTION_EXECUTE)
    public ApiResponse<PageResponse<InstrumentResponse>> usable(
            @RequestParam(required = false) String q, @RequestParam(required = false) UUID facilityId) {
        return ApiResponse.ok(instruments.list(
                q, null, facilityId, true, Pages.of(0, 100, null, SORTABLE, "instrumentCode")));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Require.CALIBRATION_MANAGE)
    public ApiResponse<InstrumentResponse> create(@Valid @RequestBody SaveInstrumentRequest request) {
        return ApiResponse.ok(instruments.create(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<InstrumentResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(instruments.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Require.CALIBRATION_MANAGE)
    public ApiResponse<InstrumentResponse> update(
            @PathVariable UUID id, @Valid @RequestBody SaveInstrumentRequest request) {
        return ApiResponse.ok(instruments.update(id, request));
    }

    @PostMapping("/{id}/status")
    @PreAuthorize(Require.CALIBRATION_MANAGE)
    public ApiResponse<InstrumentResponse> changeStatus(
            @PathVariable UUID id, @Valid @RequestBody ChangeStatusRequest request) {
        return ApiResponse.ok(instruments.changeStatus(id, request));
    }

    @GetMapping("/{id}/calibrations")
    public ApiResponse<List<CalibrationResponse>> history(@PathVariable UUID id) {
        return ApiResponse.ok(instruments.history(id));
    }

    @PostMapping("/{id}/calibrations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(Require.CALIBRATION_MANAGE)
    public ApiResponse<CalibrationResponse> record(
            @PathVariable UUID id, @Valid @RequestBody RecordCalibrationRequest request) {
        return ApiResponse.ok(instruments.recordCalibration(id, request));
    }

    @GetMapping("/{id}/usage")
    public ApiResponse<List<UsageResponse>> usage(@PathVariable UUID id) {
        return ApiResponse.ok(instruments.usage(id));
    }

    /** Holds only a link, which still asks whoever scans it to sign in. */
    @GetMapping(value = "/{id}/qr.svg", produces = "image/svg+xml")
    public ResponseEntity<String> qr(@PathVariable UUID id) {
        instruments.get(id);
        return ResponseEntity.ok(QrCodes.svg(appUrl + "/calibration/" + id));
    }
}
