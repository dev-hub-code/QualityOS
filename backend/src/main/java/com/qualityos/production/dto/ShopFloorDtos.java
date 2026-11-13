package com.qualityos.production.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qualityos.production.entity.Machine;
import com.qualityos.production.entity.OperationEvent;
import com.qualityos.production.entity.WorkOrder;
import com.qualityos.production.entity.WorkOrderOperation;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Running work order operations on the shop floor. Quantities are in the work order's unit. */
public final class ShopFloorDtos {

    private ShopFloorDtos() {
    }

    /** @param machineId the machine to run on, if not already assigned */
    public record StartRequest(UUID machineId) {
    }

    public record StopRequest(
            @NotNull OperationEvent.LossCategory category,
            @Size(max = 1000) String reason) {
    }

    /**
     * @param setupMinutes how long setting up took, out of the time it ran
     * @param reason needed when less went through than came in
     */
    public record CompleteRequest(
            @PositiveOrZero @Digits(integer = 8, fraction = 2) BigDecimal setupMinutes,
            @Size(max = 1000) String reason) {
    }

    public record BlockRequest(
            @NotNull OperationEvent.LossCategory category,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record SkipRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    /** Both are set as given: null clears. */
    public record AssignRequest(UUID operatorId, UUID machineId) {
    }

    /**
     * Good, rejected and scrapped must add up to the quantity. Rework is the part of the rejected that goes to rework.
     */
    public record ReportRequest(
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6) BigDecimal quantity,
            @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 6) BigDecimal goodQuantity,
            @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 6) BigDecimal rejectedQuantity,
            @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 6) BigDecimal scrapQuantity,
            @PositiveOrZero @Digits(integer = 12, fraction = 6) BigDecimal reworkQuantity,
            @Size(max = 1000) String comment) {
    }

    public record ReverseRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    public record MeasurementRequest(
            @NotBlank @Size(max = 50) String parameterCode,
            @NotNull @Digits(integer = 12, fraction = 6) BigDecimal value,
            @Size(max = 1000) String comment) {
    }

    public record CommentRequest(@NotBlank @Size(max = 2000) String comment) {
    }

    /** A process parameter as frozen on the operation: the routing operation's own, then the recipe's. */
    public record ParameterLimits(
            String code,
            String name,
            String unit,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal target,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal minimum,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal maximum,
            String source) {
    }

    /**
     * One operation, with where its order stands.
     *
     * @param input what can come into it: the order quantity for the first, the good output of the one before after
     * @param waiting input not yet processed: work in progress in front of this operation
     */
    public record OperationSummary(
            UUID id,
            UUID workOrderId,
            String workOrderNumber,
            WorkOrder.Status workOrderStatus,
            WorkOrder.Priority priority,
            LocalDate plannedEnd,
            UUID productId,
            String productCode,
            String productName,
            UUID facilityId,
            String facilityCode,
            int sequence,
            String name,
            UUID workCenterId,
            String workCenterCode,
            String workCenterName,
            WorkOrderOperation.Status status,
            String statusReason,
            UUID operatorId,
            String operatorName,
            UUID machineId,
            String machineCode,
            UUID uomId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal orderQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal input,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal processed,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal goodQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rejectedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal scrapQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reworkQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal waiting,
            Instant startedAt,
            Instant completedAt) {
    }

    public record ReportResponse(
            UUID id,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal goodQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rejectedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal scrapQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reworkQuantity,
            UUID reversesReportId,
            boolean reversed,
            String comment,
            UUID reportedBy,
            String reportedByName,
            Instant reportedAt) {
    }

    public record MeasurementResponse(
            UUID id,
            String parameterCode,
            String parameterName,
            String unit,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal target,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal minimum,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal maximum,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal value,
            boolean withinLimits,
            String comment,
            UUID measuredBy,
            String measuredByName,
            Instant measuredAt) {
    }

    public record EventResponse(
            UUID id,
            OperationEvent.Type eventType,
            WorkOrderOperation.Status fromStatus,
            WorkOrderOperation.Status toStatus,
            OperationEvent.LossCategory lossCategory,
            String operatorName,
            String machineCode,
            String comment,
            UUID by,
            String byName,
            Instant at) {
    }

    public record MachineOption(UUID id, String code, String name, Machine.Status status, boolean busy) {
    }

    public record PersonOption(UUID id, String name) {
    }

    /**
     * @param runningSeconds time spent running so far, without pauses
     * @param operators who it can be given to: members who may run operations at the order's facility
     */
    public record OperationDetail(
            OperationSummary operation,
            String instructions,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal plannedSetupMinutes,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal plannedRunMinutes,
            long runningSeconds,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal actualSetupMinutes,
            WorkOrderOperation.Status blockedFrom,
            boolean previousOpen,
            List<ParameterLimits> parameters,
            List<ReportResponse> reports,
            List<MeasurementResponse> measurements,
            List<EventResponse> events,
            List<MachineOption> machines,
            List<PersonOption> operators) {
    }
}
