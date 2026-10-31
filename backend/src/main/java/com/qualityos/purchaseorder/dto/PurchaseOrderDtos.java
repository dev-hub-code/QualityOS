package com.qualityos.purchaseorder.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PurchaseOrderDtos {

    private PurchaseOrderDtos() {
    }

    public record SavePurchaseOrderRequest(
            @NotNull UUID supplierId,
            @NotNull UUID facilityId,
            @NotNull LocalDate orderDate,
            LocalDate expectedDate,
            @Size(max = 1000) String notes,
            @NotEmpty List<@Valid ItemRequest> items,
            Long version) {
    }

    public record ItemRequest(
            @NotNull UUID materialId,
            @NotNull @Positive @Digits(integer = 12, fraction = 6) BigDecimal quantity,
            @PositiveOrZero @Digits(integer = 12, fraction = 6) BigDecimal unitPrice) {
    }

    public record PurchaseOrderSummary(
            UUID id,
            String poNumber,
            UUID supplierId,
            String supplierName,
            String status,
            LocalDate orderDate,
            LocalDate expectedDate) {
    }

    public record PurchaseOrderDetail(
            UUID id,
            String poNumber,
            UUID supplierId,
            String supplierName,
            UUID facilityId,
            String status,
            LocalDate orderDate,
            LocalDate expectedDate,
            String notes,
            List<ItemResponse> items,
            long version) {
    }

    public record ItemResponse(
            UUID id,
            int lineNumber,
            UUID materialId,
            String materialCode,
            String materialName,
            UUID uomId,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal orderedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal receivedQuantity,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unitPrice) {
    }

    /** The order details a receipt must copy. */
    public record ReceiptTarget(UUID purchaseOrderId, UUID supplierId, UUID facilityId, List<ReceiptLine> lines) {
    }

    public record ReceiptLine(UUID itemId, UUID materialId, UUID uomId) {
    }
}
