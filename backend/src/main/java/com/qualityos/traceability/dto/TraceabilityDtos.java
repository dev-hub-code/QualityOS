package com.qualityos.traceability.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qualityos.inspection.dto.InspectionDtos.InspectionHistory;
import com.qualityos.quality.dto.QualityDtos.CapaHistory;
import com.qualityos.quality.dto.QualityDtos.NcrHistory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class TraceabilityDtos {

    private TraceabilityDtos() {
    }

    public record SaveCustomerRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 15) String gstin,
            @Size(max = 200) String contactPerson,
            @Email @Size(max = 254) String email,
            @Size(max = 30) String phone,
            @Size(max = 500) String address) {
    }

    public record CustomerResponse(
            UUID id,
            String code,
            String name,
            String gstin,
            String contactPerson,
            String email,
            String phone,
            String address) {
    }

    public record CreateBatchRequest(
            @NotNull UUID productId,
            @NotNull UUID facilityId,
            @NotNull @Positive @Digits(integer = 12, fraction = 6) BigDecimal plannedQuantity) {
    }

    /** Names one source: a received lot or an earlier production batch. */
    public record ConsumeRequest(
            UUID receiptItemId,
            UUID parentBatchId,
            @NotNull @Positive @Digits(integer = 12, fraction = 6) BigDecimal quantity) {
    }

    public record CompleteBatchRequest(
            @NotNull @Positive @Digits(integer = 12, fraction = 6) BigDecimal producedQuantity) {
    }

    public record AddSerialsRequest(@NotEmpty @Size(max = 1000) List<@NotBlank @Size(max = 100) String> serialNumbers) {
    }

    public record ConsumptionRef(UUID id, UUID childBatchId, UUID lotId, UUID parentBatchId, BigDecimal quantity) {
    }

    /** Names one target: a lot, or a consumption. The change fields left null stay as they are. */
    public record CreateCorrectionRequest(
            UUID receiptItemId,
            UUID consumptionId,
            @NotBlank @Size(max = 1000) String reason,
            UUID materialId,
            UUID supplierId,
            @Size(max = 100) String supplierBatch,
            @Positive @Digits(integer = 12, fraction = 6) BigDecimal quantity,
            UUID correctedLotId) {
    }

    /** @param impactAcknowledged required when the material has gone further than the lot itself */
    public record ApproveCorrectionRequest(@Size(max = 1000) String note, boolean impactAcknowledged) {
    }

    public record CorrectionNoteRequest(@NotBlank @Size(max = 1000) String note) {
    }

    public record CorrectionChange(String field, String originalValue, String correctedValue) {
    }

    public record CorrectionApprovalView(String approverName, String note, Instant at) {
    }

    /** How far the material has gone. Anything here calls for a second approver. */
    public record CorrectionImpact(boolean inspected, List<String> batches, List<String> customers) {
    }

    public record CorrectionSummary(
            UUID id,
            String correctionNumber,
            String targetType,
            String status,
            String subject,
            String reason,
            String requestedByName,
            Instant createdAt) {
    }

    public record CorrectionDetail(
            UUID id,
            String correctionNumber,
            String targetType,
            String status,
            String subject,
            UUID productionBatchId,
            String reason,
            UUID requestedBy,
            String requestedByName,
            Instant createdAt,
            boolean additionalApproval,
            int approvalsNeeded,
            List<CorrectionChange> changes,
            List<CorrectionApprovalView> approvals,
            CorrectionImpact impact,
            String decisionNote,
            Instant appliedAt) {
    }

    public record ProductionBatchView(
            UUID id,
            String batchNumber,
            UUID productId,
            String productCode,
            String productName,
            UUID facilityId,
            String status,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal plannedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal producedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal availableQuantity,
            Instant createdAt,
            Instant completedAt) {
    }

    public record ReverseRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** @param reversalReason set when this entry was corrected, and then it no longer counts */
    public record Consumption(
            UUID id,
            String sourceBatch,
            String description,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            Instant at,
            String reversalReason) {
    }

    public record BatchDetail(ProductionBatchView batch, List<Consumption> consumptions, List<String> serialNumbers) {
    }

    public record DispatchItemRequest(
            @NotNull UUID productionBatchId,
            @NotNull @Positive @Digits(integer = 12, fraction = 6) BigDecimal quantity,
            @Size(max = 1000) List<@NotBlank @Size(max = 100) String> serialNumbers) {
    }

    public record CreateDispatchRequest(
            @NotNull UUID customerId,
            @NotNull LocalDate dispatchedOn,
            @Size(max = 200) String reference,
            @NotEmpty List<@Valid DispatchItemRequest> items) {
    }

    public record DispatchLine(
            UUID id,
            String batchNumber,
            String productCode,
            String productName,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal returnedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal expectedQuantity) {
    }

    public record ReturnLineRequest(
            @NotNull UUID dispatchItemId,
            @NotNull @Positive @Digits(integer = 12, fraction = 6) BigDecimal quantity,
            @Size(max = 1000) List<@NotBlank @Size(max = 100) String> serialNumbers) {
    }

    public record ConfirmShipmentRequest(
            @NotNull LocalDate shippedOn,
            @Size(max = 200) String carrierReference) {
    }

    /** @param expected the goods are on their way, and {@code reversalDate} is when they should arrive */
    public record CreateReversalRequest(
            @NotBlank @Size(max = 100) String returnReference,
            @NotNull LocalDate reversalDate,
            @NotBlank @Size(max = 1000) String reason,
            Boolean expected,
            @NotEmpty List<@Valid ReturnLineRequest> items) {

        public boolean isExpected() {
            return Boolean.TRUE.equals(expected);
        }
    }

    public record ReceiveReturnRequest(@NotNull LocalDate receivedOn) {
    }

    /** A return line, as inspection needs to see it. */
    public record ReturnedGoods(UUID id, UUID productionBatchId, BigDecimal quantity) {
    }

    public record ReversalItemView(
            UUID id,
            UUID dispatchItemId,
            String batchNumber,
            String productCode,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            List<String> serialNumbers,
            String status,
            String disposition) {
    }

    public record ReversalView(
            UUID id,
            String reversalNumber,
            UUID dispatchId,
            String dispatchNumber,
            String customerName,
            String returnReference,
            LocalDate reversalDate,
            String reason,
            String status,
            String recordedByName,
            Instant createdAt,
            Instant closedAt,
            List<ReversalItemView> items) {
    }

    /** @param status NOT_REVERSED, PARTIALLY_REVERSED or FULLY_REVERSED */
    public record ReversalSummary(
            UUID dispatchId,
            String status,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal dispatchedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal reversedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal remainingQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal expectedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal pendingQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal acceptedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal rejectedQuantity) {
    }

    public record DispatchResponse(
            UUID id,
            String dispatchNumber,
            UUID customerId,
            String customerName,
            LocalDate dispatchedOn,
            String reference,
            List<DispatchLine> lines,
            Instant createdAt,
            String reversalReason,
            Instant reversedAt,
            LocalDate shippedOn,
            String carrierReference) {
    }

    /** @param matchedOn which field the term was found in */
    /** @param serialNumber set when the hit is one unit, not a batch or a lot */
    public record SearchHit(String batchNumber, String serialNumber, String kind, String description, String matchedOn) {
    }

    /** A lot or a production batch in a trace. */
    public record TraceNode(
            UUID id,
            String kind,
            String batchNumber,
            String code,
            String name,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            String status,
            String supplierName,
            String supplierBatch,
            String receiptNumber,
            String purchaseOrderNumber) {
    }

    public record TraceEdge(
            String fromBatch, String toBatch, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity) {
    }

    public record TimelineEvent(
            Instant at, String kind, String title, String batchNumber, String status, String link) {
    }

    /**
     * What a person with this permission may see of a batch's history. Inspections, NCRs and CAPAs are left out
     * for anyone who could not open them directly.
     */
    /**
     * One dispatch that reached, or may have reached, a customer. A customer stays listed after returning everything.
     *
     * @param outstandingQuantity what the customer still holds, as far as anyone has recorded
     */
    public record RecallLine(
            UUID dispatchId,
            String dispatchNumber,
            UUID customerId,
            String customerName,
            LocalDate dispatchedOn,
            LocalDate shippedOn,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal returnedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal outstandingQuantity) {

        public String quantityLabel() {
            return quantity.stripTrailingZeros().toPlainString();
        }

        public String returnedLabel() {
            return returnedQuantity.stripTrailingZeros().toPlainString();
        }

        public String outstandingLabel() {
            return outstandingQuantity.stripTrailingZeros().toPlainString();
        }
    }

    /** @param returned this unit came back from this dispatch */
    public record SerialDelivery(
            UUID dispatchId,
            String dispatchNumber,
            String customerName,
            LocalDate dispatchedOn,
            LocalDate shippedOn,
            boolean returned) {
    }

    public record SerialTrace(
            String serialNumber,
            String batchNumber,
            String productCode,
            String productName,
            String batchStatus,
            Instant manufacturedAt,
            List<InspectionHistory> inspections,
            List<SerialDelivery> deliveries) {
    }

    /** One unit in search results. The dispatch shown is where the unit is now, or where it last came back from. */
    public record SerialSearchResult(
            String serialNumber,
            String productCode,
            String productName,
            String batchNumber,
            String facilityName,
            Instant manufacturedAt,
            String inspectionStatus,
            String qualityStatus,
            String dispatchStatus,
            String customerName,
            String dispatchNumber,
            LocalDate dispatchedOn) {
    }

    public record SerialFilter(
            String query,
            String field,
            UUID facilityId,
            LocalDate madeFrom,
            LocalDate madeTo,
            LocalDate dispatchedFrom,
            LocalDate dispatchedTo) {
    }

    /** What a recall covers: the batches named, everything made from them, and where it all went. */
    public record RecallScope(
            List<ProductionBatchView> batches,
            List<DispatchResponse> dispatches,
            List<RecallLine> lines,
            List<String> serialNumbers,
            List<InspectionHistory> inspections,
            List<NcrHistory> ncrs) {
    }

    public record TraceReport(
            TraceNode subject,
            List<TraceNode> backward,
            List<TraceNode> forward,
            List<TraceEdge> edges,
            List<DispatchResponse> dispatches,
            List<ReversalView> returns,
            List<String> affectedCustomers,
            List<String> unconfirmedCustomers,
            List<RecallLine> recall,
            List<String> serialNumbers,
            List<InspectionHistory> inspections,
            List<NcrHistory> ncrs,
            List<CapaHistory> capas,
            List<TimelineEvent> timeline) {
    }
}
