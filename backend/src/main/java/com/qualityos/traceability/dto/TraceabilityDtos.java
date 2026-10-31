package com.qualityos.traceability.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qualityos.inspection.dto.InspectionDtos.InspectionHistory;
import com.qualityos.quality.dto.QualityDtos.CapaHistory;
import com.qualityos.quality.dto.QualityDtos.NcrHistory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
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
            @Size(max = 15) String gstin) {
    }

    public record CustomerResponse(UUID id, String code, String name, String gstin) {
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
            String batchNumber,
            String productCode,
            String productName,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity) {
    }

    public record DispatchResponse(
            UUID id,
            String dispatchNumber,
            String customerName,
            LocalDate dispatchedOn,
            String reference,
            List<DispatchLine> lines,
            Instant createdAt,
            String reversalReason,
            Instant reversedAt) {
    }

    /** @param matchedOn which field the term was found in */
    public record SearchHit(String batchNumber, String kind, String description, String matchedOn) {
    }

    /** A lot or a production batch in a trace. */
    public record TraceNode(
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
    public record TraceReport(
            TraceNode subject,
            List<TraceNode> backward,
            List<TraceNode> forward,
            List<TraceEdge> edges,
            List<DispatchResponse> dispatches,
            List<String> affectedCustomers,
            List<String> serialNumbers,
            List<InspectionHistory> inspections,
            List<NcrHistory> ncrs,
            List<CapaHistory> capas,
            List<TimelineEvent> timeline) {
    }
}
