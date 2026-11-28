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

    /**
     * A line. {@code uomId} is the unit it is ordered in, such as DRUM for a material kept in KG, when not the
     * material's own; the unit price is per that unit.
     */
    public record ItemRequest(
            @NotNull UUID materialId,
            @NotNull @Positive @Digits(integer = 12, fraction = 6) BigDecimal quantity,
            @PositiveOrZero @Digits(integer = 12, fraction = 6) BigDecimal unitPrice,
            UUID uomId) {
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
            /* Per the ordered unit: enteredUomId when there is one, otherwise uomId. */
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unitPrice,
            /* What was ordered when in another unit than the material's, such as 4 DRUM; ordered is then 800 KG. */
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal enteredQuantity,
            UUID enteredUomId) {
    }

    /** The order details a receipt must copy. */
    public record ReceiptTarget(UUID purchaseOrderId, UUID supplierId, UUID facilityId, List<ReceiptLine> lines) {
    }

    public record ReceiptLine(UUID itemId, UUID materialId, UUID uomId) {
    }

    /** A purchase price per unit of {@code uomId}, in the organisation's currency; null when the line has none. */
    public record UnitPrice(UUID materialId, UUID uomId, BigDecimal price) {
    }

    /** Why an order is cancelled: needed once it was issued to the supplier. */
    public record CancelOrderRequest(@jakarta.validation.constraints.Size(max = 1000) String reason) {
    }
}
