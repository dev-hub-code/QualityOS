package com.qualityos.calibration.dto;

import com.qualityos.calibration.entity.CalibrationRecord;
import com.qualityos.calibration.entity.Instrument;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class CalibrationDtos {

    private CalibrationDtos() {
    }

    /** @param nextDueOn used when the instrument is registered, and afterwards calibration records move it */
    public record SaveInstrumentRequest(
            @NotBlank @Size(max = 50) String instrumentCode,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 100) String category,
            @Size(max = 200) String manufacturer,
            @Size(max = 200) String model,
            @Size(max = 100) String serialNumber,
            @NotNull UUID facilityId,
            @Size(max = 200) String location,
            UUID custodianId,
            @Positive int frequencyDays,
            @NotNull LocalDate nextDueOn) {
    }

    public record ChangeStatusRequest(@NotNull Instrument.Status status, @NotBlank @Size(max = 2000) String reason) {
    }

    public record RecordCalibrationRequest(
            @NotNull @PastOrPresent LocalDate calibratedOn,
            @NotNull CalibrationRecord.Result result,
            @NotBlank @Size(max = 200) String performedBy,
            @Size(max = 100) String certificateNumber,
            @Size(max = 4000) String notes) {
    }

    /** @param dueState OK, DUE_SOON or OVERDUE, by the organisation's own calendar */
    public record InstrumentResponse(
            UUID id,
            String instrumentCode,
            String name,
            String category,
            String manufacturer,
            String model,
            String serialNumber,
            UUID facilityId,
            String location,
            UUID custodianId,
            String custodianName,
            int frequencyDays,
            LocalDate lastCalibratedOn,
            LocalDate nextDueOn,
            String status,
            String statusReason,
            String dueState) {
    }

    public record CalibrationResponse(
            UUID id,
            LocalDate calibratedOn,
            String result,
            String performedBy,
            String certificateNumber,
            String notes,
            LocalDate nextDueOn,
            Instant recordedAt) {
    }

    /** @param suspect measured after the last good calibration of an instrument that has since failed */
    public record UsageResponse(UUID inspectionId, String inspectionNumber, LocalDate usedOn, boolean suspect) {
    }

    public record UsedInstrument(UUID id, String instrumentCode, String name, LocalDate nextDueOn) {
    }
}
