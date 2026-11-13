package com.qualityos.production.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qualityos.production.entity.WorkOrder;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class SchedulingDtos {

    private SchedulingDtos() {
    }

    /** One work centre's day: capacity from shifts and machines, less planned downtime, against the load on it. */
    public record DayLoad(
            LocalDate date,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal capacityMinutes,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal plannedDowntimeMinutes,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal loadMinutes,
            boolean overloaded) {
    }

    public record CentreLoad(UUID id, String code, String name, int units, List<DayLoad> days) {
    }

    public record ScheduledOrder(
            UUID id,
            String workOrderNumber,
            String productCode,
            WorkOrder.Status status,
            WorkOrder.Priority priority,
            LocalDate plannedStart,
            LocalDate plannedEnd,
            /** Planned setup and run time not yet worked, in minutes, over all its operations. */
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal remainingMinutes,
            List<String> warnings) {
    }

    public record Conflict(
            UUID workCenterId,
            String workCenterCode,
            LocalDate date,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal capacityMinutes,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal loadMinutes,
            List<String> workOrderNumbers) {
    }

    /**
     * A facility's load against capacity, day by day in the organisation's time zone. Each order's remaining time at a
     * work centre is spread evenly over the working days left in its planned dates.
     */
    public record SchedulingBoard(
            UUID facilityId,
            LocalDate from,
            LocalDate to,
            String timezone,
            /** False when the facility has no shifts, so there is no capacity to plan against. */
            boolean shiftsDefined,
            List<CentreLoad> workCenters,
            List<ScheduledOrder> orders,
            List<Conflict> conflicts) {
    }

    /** Moves an order's planned dates, and its priority if given. Allowed until it is completed or cancelled. */
    public record RescheduleRequest(
            @NotNull LocalDate plannedStart,
            @NotNull LocalDate plannedEnd,
            WorkOrder.Priority priority,
            @NotBlank @Size(max = 1000) String reason) {
    }

    /** The order's place on the board after the move, with any capacity warnings. */
    public record RescheduleResponse(ScheduledOrder order) {
    }
}
