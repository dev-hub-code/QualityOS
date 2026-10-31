package com.qualityos.materialreceipt.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class MaterialReceiptDtos {

    private MaterialReceiptDtos() {
    }

    /** One order line may appear several times, when a delivery holds several supplier batches. */
    public record CreateReceiptRequest(
            @NotNull UUID purchaseOrderId,
            @NotNull @PastOrPresent LocalDate receivedOn,
            @Size(max = 100) String deliveryNote,
            @Size(max = 1000) String notes,
            @NotEmpty List<@Valid ReceiptItemRequest> items) {
    }

    public record ReceiptItemRequest(
            @NotNull UUID purchaseOrderItemId,
            @NotBlank @Size(max = 100) String supplierBatch,
            @NotNull @Positive @Digits(integer = 12, fraction = 6) BigDecimal quantity) {
    }

    public record VoidReceiptRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    public record ReceiptSummary(
            UUID id,
            String grnNumber,
            UUID purchaseOrderId,
            UUID supplierId,
            String supplierName,
            LocalDate receivedOn,
            String status) {
    }

    public record ReceiptDetail(
            UUID id,
            String grnNumber,
            UUID purchaseOrderId,
            UUID supplierId,
            String supplierName,
            UUID facilityId,
            LocalDate receivedOn,
            String deliveryNote,
            String notes,
            String status,
            String voidReason,
            List<ReceiptItemResponse> items) {
    }

    public record ReceiptItemResponse(
            UUID id,
            UUID purchaseOrderItemId,
            UUID materialId,
            String materialCode,
            String materialName,
            UUID uomId,
            String supplierBatch,
            String internalBatch,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            boolean inspectionRequired,
            String inspectionStatus) {
    }
}
