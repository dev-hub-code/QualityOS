package com.qualityos.production.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qualityos.production.entity.WorkOrder;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Receiving a work order's finished goods and closing it. */
public final class CompletionDtos {

    private CompletionDtos() {
    }

    /**
     * @param quantity what goes into finished goods, in the order's unit. With operations, it must be what they turned
     *     out good (and rework accepted); without, it is required
     * @param shortReason needed when less is received than ordered, beyond the organisation's tolerance
     * @param varianceReason needed when a material was used more or less than the received quantity needed, beyond
     *     the organisation's tolerance
     * @param serialNumbers one per unit when the organisation tracks serials for discrete products
     */
    public record ReceiptRequest(
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 6) BigDecimal quantity,
            @Size(max = 1000) String shortReason,
            @Size(max = 1000) String varianceReason,
            @Size(max = 1000) List<@NotBlank @Size(max = 100) String> serialNumbers) {
    }

    /** @param reason needed when the order closes short */
    public record CloseRequest(@Size(max = 1000) String reason) {
    }

    /**
     * One material line at receipt, in the line's unit.
     *
     * @param expected what the received quantity needed: the line's requirement, scaled
     * @param percent the difference against what was expected, in percent
     * @param outside beyond the organisation's tolerance
     */
    public record MaterialVariance(
            int lineNumber,
            String componentCode,
            String componentName,
            UUID uomId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal expected,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal issued,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal difference,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal percent,
            boolean outside) {
    }

    /**
     * Where an order stands against receiving and closing, worked out now.
     *
     * @param expectedQuantity what the operations turned out good, with rework accepted; null without operations
     * @param receiveBlockers why finished goods cannot be received yet
     * @param closeBlockers why the order cannot be closed yet, once received
     * @param closesAs how it would close: completed, or partially completed when short
     */
    public record CompletionResponse(
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal orderedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal expectedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rejectedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal scrapQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reworkedQuantity,
            boolean serialsRequired,
            List<MaterialVariance> materials,
            List<String> receiveBlockers,
            ReceiptResponse receipt,
            List<String> closeBlockers,
            WorkOrder.Status closesAs,
            Instant completedAt,
            String completedByName) {
    }

    public record ReceiptResponse(
            UUID id,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal orderedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal expectedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rejectedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal scrapQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reworkedQuantity,
            String shortReason,
            String varianceReason,
            List<MaterialVariance> materials,
            int serialCount,
            UUID receivedBy,
            String receivedByName,
            Instant receivedAt) {
    }

    /** An operation as the batch's production record shows it: who ran it and on what. */
    public record OperationRecord(
            int sequence,
            String name,
            String workCenterCode,
            String status,
            List<String> operators,
            List<String> machines,
            Instant startedAt,
            Instant completedAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal goodQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rejectedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal scrapQuantity) {
    }

    /**
     * How a production batch was made, for its trace: the work order, the frozen definitions, each operation with its
     * operators and machines, quality gates and rework, and the receipt.
     */
    public record ProductionRecord(
            UUID workOrderId,
            String workOrderNumber,
            WorkOrder.Status status,
            String definitionKind,
            Integer definitionRevision,
            Integer routingRevision,
            List<OperationRecord> operations,
            List<String> gates,
            List<String> reworkOrders,
            ReceiptResponse receipt) {
    }
}
