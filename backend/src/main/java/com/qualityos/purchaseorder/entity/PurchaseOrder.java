package com.qualityos.purchaseorder.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Entity
@Table(name = "purchase_orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrder extends TenantOwnedEntity {

    public enum Status { DRAFT, ISSUED, PARTIALLY_RECEIVED, RECEIVED, CLOSED, CANCELLED }

    private String poNumber;

    @Setter
    private UUID supplierId;

    @Setter
    private UUID facilityId;

    @Enumerated(EnumType.STRING)
    private Status status = Status.DRAFT;

    @Setter
    private LocalDate orderDate;

    @Setter
    private LocalDate expectedDate;

    @Setter
    private String notes;

    @OrderBy("lineNumber")
    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PurchaseOrderItem> items = new ArrayList<>();

    public PurchaseOrder(String poNumber) {
        this.poNumber = poNumber;
    }

    public void addItem(UUID materialId, UUID uomId, BigDecimal quantity, BigDecimal unitPrice) {
        items.add(new PurchaseOrderItem(this, items.size() + 1, materialId, uomId, quantity, unitPrice));
    }

    public void requireDraft() {
        if (status != Status.DRAFT) {
            throw new BusinessRuleException("Only a draft purchase order can be changed.");
        }
    }

    public void issue() {
        requireDraft();
        if (items.isEmpty()) {
            throw new BusinessRuleException("Add at least one item before issuing.");
        }
        status = Status.ISSUED;
    }

    public void cancel() {
        if (status != Status.DRAFT && status != Status.ISSUED) {
            throw new BusinessRuleException("A purchase order with receipts cannot be cancelled. Close it instead.");
        }
        status = Status.CANCELLED;
    }

    /** Ends an order that will not be fully delivered. */
    public void close() {
        if (status != Status.ISSUED && status != Status.PARTIALLY_RECEIVED && status != Status.RECEIVED) {
            throw new BusinessRuleException("A purchase order in status %s cannot be closed.".formatted(status));
        }
        status = Status.CLOSED;
    }

    public void receive(PurchaseOrderItem item, BigDecimal quantity) {
        if (status != Status.ISSUED && status != Status.PARTIALLY_RECEIVED) {
            throw new BusinessRuleException("Material can only be received against an open purchase order.");
        }
        if (quantity.compareTo(item.remainingQuantity()) > 0) {
            throw new BusinessRuleException("Line %d: only %s remains to be received."
                    .formatted(item.getLineNumber(), item.remainingQuantity().stripTrailingZeros().toPlainString()));
        }
        item.adjustReceived(quantity);
        refreshReceiptStatus();
    }

    public void reverseReceipt(PurchaseOrderItem item, BigDecimal quantity) {
        item.adjustReceived(quantity.negate());
        if (status != Status.CLOSED) {
            refreshReceiptStatus();
        }
    }

    private void refreshReceiptStatus() {
        boolean complete = items.stream().allMatch(i -> i.remainingQuantity().signum() == 0);
        boolean started = items.stream().anyMatch(i -> i.getReceivedQuantity().signum() > 0);
        status = complete ? Status.RECEIVED : started ? Status.PARTIALLY_RECEIVED : Status.ISSUED;
    }
}
