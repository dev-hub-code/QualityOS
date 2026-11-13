package com.qualityos.production.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qualityos.production.entity.ProductionPlan;
import com.qualityos.production.entity.WorkOrder;
import com.qualityos.production.entity.WorkOrderOperation;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Production plans and work orders. */
public final class WorkOrderDtos {

    private WorkOrderDtos() {
    }

    // Production plans

    public record SavePlanRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull UUID facilityId,
            @NotNull LocalDate periodStart,
            @NotNull LocalDate periodEnd,
            @Size(max = 2000) String notes) {
    }

    public record SavePlanLineRequest(
            @NotNull UUID productId,
            @NotNull @Positive @Digits(integer = 12, fraction = 6) BigDecimal quantity,
            @NotNull LocalDate dueDate,
            @Size(max = 1000) String notes) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    public record PlanResponse(
            UUID id,
            String planNumber,
            String name,
            UUID facilityId,
            String facilityCode,
            LocalDate periodStart,
            LocalDate periodEnd,
            ProductionPlan.Status status,
            String statusReason,
            String notes,
            UUID approvedBy,
            String approvedByName,
            Instant approvedAt,
            Instant createdAt) {
    }

    public record PlanWorkOrder(
            UUID id, String workOrderNumber, WorkOrder.Status status,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity) {
    }

    /**
     * @param orderedQuantity what its work orders, cancelled ones aside, add up to
     * @param overOrdered more is ordered than planned, which is allowed but shown
     */
    public record PlanLineResponse(
            UUID id,
            UUID productId,
            String productCode,
            String productName,
            UUID uomId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            LocalDate dueDate,
            String notes,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal orderedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal remainingQuantity,
            boolean overOrdered,
            List<PlanWorkOrder> workOrders) {
    }

    public record PlanDetail(PlanResponse plan, List<PlanLineResponse> lines) {
    }

    // Work orders

    /** A work order raised from a plan line takes the line's product, and the plan's facility. */
    public record SaveWorkOrderRequest(
            @NotNull UUID productId,
            @NotNull UUID facilityId,
            UUID productionLineId,
            @NotNull @Positive @Digits(integer = 12, fraction = 6) BigDecimal quantity,
            @NotNull LocalDate plannedStart,
            @NotNull LocalDate plannedEnd,
            WorkOrder.Priority priority,
            @Size(max = 2000) String notes,
            UUID planLineId) {
    }

    public record NoteRequest(@Size(max = 1000) String note) {
    }

    public record SendBackWorkOrderRequest(@NotBlank @Size(max = 1000) String note) {
    }

    public record WorkOrderResponse(
            UUID id,
            String workOrderNumber,
            UUID productId,
            String productCode,
            String productName,
            UUID facilityId,
            String facilityCode,
            UUID productionLineId,
            String productionLineCode,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            UUID uomId,
            LocalDate plannedStart,
            LocalDate plannedEnd,
            WorkOrder.Priority priority,
            WorkOrder.Status status,
            WorkOrder.Status heldFrom,
            String statusReason,
            String notes,
            UUID planId,
            String planNumber,
            UUID planLineId,
            UUID productionBatchId,
            String productionBatchNumber,
            UUID submittedBy,
            String submittedByName,
            Instant submittedAt,
            UUID approvedBy,
            String approvedByName,
            Instant approvedAt,
            UUID releasedBy,
            String releasedByName,
            Instant releasedAt,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt) {
    }

    /** One component a released order needs, in the component's own unit. */
    public record RequirementResponse(
            int lineNumber,
            String componentType,
            UUID componentId,
            String componentCode,
            String componentName,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal requiredQuantity,
            UUID uomId) {
    }

    public record WorkOrderOperationResponse(
            UUID id,
            int sequence,
            String name,
            UUID workCenterId,
            String workCenterCode,
            String workCenterName,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal plannedSetupMinutes,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal plannedRunMinutes,
            String instructions,
            WorkOrderOperation.Status status,
            String statusReason,
            String operatorName,
            String machineCode,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal input,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal goodQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rejectedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal scrapQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal waiting) {
    }

    public record StatusChangeResponse(
            WorkOrder.Status fromStatus, WorkOrder.Status toStatus, String reason, UUID changedBy, String changedByName,
            Instant changedAt) {
    }

    /**
     * What a release would use, or did use. Before release it names the revisions in effect on the planned start, and
     * {@code problems} says what would stop the release.
     */
    public record ReleaseDefinition(
            String definitionKind,
            UUID definitionId,
            Integer definitionRevision,
            UUID routingId,
            Integer routingRevision,
            List<String> problems) {
    }

    public record WorkOrderDetail(
            WorkOrderResponse workOrder,
            ReleaseDefinition definition,
            List<RequirementResponse> materials,
            List<WorkOrderOperationResponse> operations,
            List<StatusChangeResponse> history) {
    }
}
