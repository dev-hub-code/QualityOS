package com.qualityos.production.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qualityos.production.entity.QualityCheckpoint;
import com.qualityos.production.entity.ReworkOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Quality checkpoints, gates on work orders, quality holds and rework orders. */
public final class ProductionQualityDtos {

    private ProductionQualityDtos() {
    }

    // Checkpoints

    public record SaveCheckpointRequest(
            @NotBlank @Size(max = 200) String name,
            @NotNull QualityCheckpoint.Stage stage,
            @NotNull UUID templateId,
            UUID productId,
            UUID workCenterId,
            @Min(1) Integer operationSequence,
            Boolean mandatory) {
    }

    public record CheckpointResponse(
            UUID id,
            String name,
            QualityCheckpoint.Stage stage,
            UUID templateId,
            String templateCode,
            String templateName,
            String inspectionType,
            UUID productId,
            String productCode,
            UUID workCenterId,
            String workCenterCode,
            Integer operationSequence,
            boolean mandatory,
            boolean archived) {
    }

    // Gates

    public enum GateStatus { PENDING, IN_INSPECTION, ON_HOLD, PASSED, FAILED }

    public record GateInspection(
            UUID id, String inspectionNumber, String status, String finalResult, String disposition, Instant createdAt) {
    }

    /**
     * @param status where the latest inspection leaves the gate
     * @param inspections every inspection started for it, oldest first; earlier ones stay as evidence
     */
    public record GateResponse(
            UUID id,
            String name,
            QualityCheckpoint.Stage stage,
            boolean mandatory,
            UUID operationId,
            Integer operationSequence,
            String operationName,
            UUID templateId,
            String templateCode,
            String templateName,
            GateStatus status,
            List<GateInspection> inspections) {
    }

    /** @param templateId the template to reinspect with; left out, the one that fits the batch now */
    public record ReinspectRequest(UUID templateId) {
    }

    public record HoldRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    public record NcrLink(UUID id, String ncrNumber, String title, String status, String severity) {
    }

    public record CapaLink(UUID id, String capaNumber, String title, String status, UUID ncrId) {
    }

    /**
     * Rejected production and what became of it, in the work order's unit.
     *
     * @param rejected rejected on the operations
     * @param toRework of the rejected, marked for rework
     * @param inRework on rework orders still open
     * @param reworkedAccepted reworked and accepted on reinspection
     */
    public record RejectedSummary(
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rejected,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal toRework,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal scrapped,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal inRework,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reworkedAccepted) {
    }

    /**
     * Quality on one work order.
     *
     * @param batchStatus the production batch's status, or null before production starts
     * @param unresolved one sentence for each mandatory gate not passed and each rework order still open: completion
     *     waits for all of them
     */
    public record QualityResponse(
            UUID batchId,
            String batchNumber,
            String batchStatus,
            boolean onHold,
            List<GateResponse> gates,
            List<NcrLink> ncrs,
            List<CapaLink> capas,
            List<ReworkSummary> reworkOrders,
            RejectedSummary rejected,
            List<String> unresolved) {
    }

    // Rework

    public record StepRequest(@NotBlank @Size(max = 1000) String description, UUID workCenterId) {
    }

    /**
     * @param operationId the operation whose rejected goods are reworked, if any
     * @param inspectionId an inspection of the batch disposed of as rework, if any
     */
    public record CreateReworkRequest(
            @NotNull UUID workOrderId,
            UUID operationId,
            UUID ncrId,
            UUID inspectionId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6) BigDecimal quantity,
            @NotBlank @Size(max = 1000) String reason,
            @Size(max = 4000) String instructions,
            @NotNull @Size(min = 1, max = 50) List<@Valid StepRequest> steps) {
    }

    public record CompleteReworkRequest(
            @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 6) BigDecimal reworkedQuantity,
            @NotNull @PositiveOrZero @Digits(integer = 12, fraction = 6) BigDecimal scrappedQuantity) {
    }

    public record StepDoneRequest(@Size(max = 1000) String note) {
    }

    public record ReworkSummary(
            UUID id,
            String reworkNumber,
            UUID workOrderId,
            String workOrderNumber,
            UUID productionBatchId,
            String batchNumber,
            String productCode,
            String productName,
            UUID operationId,
            Integer operationSequence,
            UUID ncrId,
            String ncrNumber,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            UUID uomId,
            ReworkOrder.Status status,
            String finalDisposition,
            Instant createdAt) {
    }

    public record StepResponse(
            UUID id, int sequence, String description, UUID workCenterId, String workCenterCode, UUID completedBy,
            String completedByName, Instant completedAt, String note) {
    }

    public record ReworkHistory(
            ReworkOrder.Status fromStatus, ReworkOrder.Status toStatus, String reason, UUID changedBy,
            String changedByName, Instant changedAt) {
    }

    /** @param reinspection the reinspection's state, or null before one is started */
    public record ReworkDetail(
            ReworkSummary rework,
            String reason,
            String instructions,
            String statusReason,
            UUID sourceInspectionId,
            String sourceInspectionNumber,
            UUID approvedBy,
            String approvedByName,
            Instant approvedAt,
            Instant startedAt,
            Instant completedAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reworkedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal scrappedQuantity,
            GateInspection reinspection,
            Instant closedAt,
            List<StepResponse> steps,
            List<ReworkHistory> history) {
    }
}
