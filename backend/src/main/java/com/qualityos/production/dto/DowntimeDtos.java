package com.qualityos.production.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qualityos.production.entity.DowntimeRecord;
import com.qualityos.production.entity.OperationEvent.LossCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class DowntimeDtos {

    private DowntimeDtos() {
    }

    /** A stop entered by hand: on a machine, or at a work centre. Planned maintenance may be entered ahead. */
    public record RecordDowntimeRequest(
            UUID machineId,
            UUID workCenterId,
            UUID workOrderId,
            @NotNull LossCategory category,
            @NotNull Instant startedAt,
            Instant endedAt,
            @NotBlank @Size(max = 1000) String reason,
            @Size(max = 200) String responsibleDepartment,
            @Size(max = 4000) String comments) {
    }

    /**
     * Corrections. Who was responsible and the comments can always be changed; the category and times only on a stop
     * entered by hand, or one still open.
     */
    public record UpdateDowntimeRequest(
            LossCategory category,
            Instant startedAt,
            Instant endedAt,
            @Size(max = 1000) String reason,
            @Size(max = 200) String responsibleDepartment,
            @Size(max = 4000) String comments) {
    }

    public record EndDowntimeRequest(Instant endedAt) {
    }

    public record DowntimeResponse(
            UUID id,
            UUID facilityId,
            String facilityCode,
            UUID workCenterId,
            String workCenterCode,
            UUID machineId,
            String machineCode,
            UUID workOrderId,
            String workOrderNumber,
            UUID operationId,
            Integer operationSequence,
            DowntimeRecord.Source source,
            LossCategory category,
            boolean planned,
            Instant startedAt,
            Instant endedAt,
            /** To the end, or to now while it is open. */
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal minutes,
            String reason,
            String responsibleDepartment,
            String comments,
            String recordedByName,
            String endedByName) {
    }

    public enum GroupBy { MACHINE, WORK_CENTER, PRODUCTION_LINE, FACILITY, CATEGORY }

    public record SummaryRow(
            String key,
            String label,
            long stops,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal minutes,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal plannedMinutes,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unplannedMinutes) {
    }

    /** Minutes within the period, in the organisation's time zone, both days included. Open stops count to now. */
    public record DowntimeSummary(
            LocalDate from,
            LocalDate to,
            String timezone,
            GroupBy groupBy,
            List<SummaryRow> rows,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalMinutes,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal plannedMinutes,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unplannedMinutes) {
    }
}
