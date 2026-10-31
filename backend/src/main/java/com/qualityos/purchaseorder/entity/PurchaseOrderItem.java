package com.qualityos.purchaseorder.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "purchase_order_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrderItem extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id")
    private PurchaseOrder purchaseOrder;

    private int lineNumber;
    private UUID materialId;
    private UUID uomId;
    private BigDecimal orderedQuantity;
    private BigDecimal receivedQuantity = BigDecimal.ZERO;
    private BigDecimal unitPrice;

    PurchaseOrderItem(
            PurchaseOrder purchaseOrder,
            int lineNumber,
            UUID materialId,
            UUID uomId,
            BigDecimal orderedQuantity,
            BigDecimal unitPrice) {
        this.purchaseOrder = purchaseOrder;
        this.lineNumber = lineNumber;
        this.materialId = materialId;
        this.uomId = uomId;
        this.orderedQuantity = orderedQuantity;
        this.unitPrice = unitPrice;
    }

    public BigDecimal remainingQuantity() {
        return orderedQuantity.subtract(receivedQuantity);
    }

    void adjustReceived(BigDecimal delta) {
        receivedQuantity = receivedQuantity.add(delta);
    }
}
