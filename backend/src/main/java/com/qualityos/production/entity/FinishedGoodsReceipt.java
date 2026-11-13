package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The finished goods a work order put into its production batch, with how the quantities and materials reconciled at
 * that moment. One per order, never changed. The goods themselves are the batch, in the existing inventory.
 */
@Getter
@Entity
@Immutable
@Table(name = "finished_goods_receipts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FinishedGoodsReceipt extends TenantOwnedEntity {

    private UUID workOrderId;
    private UUID productionBatchId;
    private BigDecimal quantity;
    private BigDecimal orderedQuantity;
    private BigDecimal expectedQuantity;
    private BigDecimal rejectedQuantity;
    private BigDecimal scrapQuantity;
    private BigDecimal reworkedQuantity;
    private String shortReason;
    private String varianceReason;

    @JdbcTypeCode(SqlTypes.JSON)
    private String materialVariances;

    private int serialCount;
    private String requestKey;

    @SuppressWarnings("java:S107")
    public FinishedGoodsReceipt(
            WorkOrder order, BigDecimal quantity, BigDecimal expectedQuantity, BigDecimal rejectedQuantity,
            BigDecimal scrapQuantity, BigDecimal reworkedQuantity, String shortReason, String varianceReason,
            String materialVariances, int serialCount, String requestKey) {
        this.workOrderId = order.getId();
        this.productionBatchId = order.getProductionBatchId();
        this.quantity = quantity;
        this.orderedQuantity = order.getQuantity();
        this.expectedQuantity = expectedQuantity;
        this.rejectedQuantity = rejectedQuantity;
        this.scrapQuantity = scrapQuantity;
        this.reworkedQuantity = reworkedQuantity;
        this.shortReason = shortReason;
        this.varianceReason = varianceReason;
        this.materialVariances = materialVariances;
        this.serialCount = serialCount;
        this.requestKey = requestKey;
    }
}
