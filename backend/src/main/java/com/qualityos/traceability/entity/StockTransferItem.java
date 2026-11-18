package com.qualityos.traceability.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Part of one lot or one batch on a transfer, and what it became at the other plant once received. */
@Getter
@Entity
@Table(name = "stock_transfer_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockTransferItem extends TenantOwnedEntity {

    private UUID transferId;
    private UUID sourceLotId;
    private UUID sourceBatchId;
    /** In the source's own unit. */
    private BigDecimal quantity;
    private UUID receivedLotId;
    private UUID receivedBatchId;

    public static StockTransferItem ofLot(UUID transferId, UUID lotId, BigDecimal quantity) {
        StockTransferItem item = new StockTransferItem();
        item.transferId = transferId;
        item.sourceLotId = lotId;
        item.quantity = quantity;
        return item;
    }

    public static StockTransferItem ofBatch(UUID transferId, UUID batchId, BigDecimal quantity) {
        StockTransferItem item = new StockTransferItem();
        item.transferId = transferId;
        item.sourceBatchId = batchId;
        item.quantity = quantity;
        return item;
    }

    public void receivedAsLot(UUID lotId) {
        this.receivedLotId = lotId;
    }

    public void receivedAsBatch(UUID batchId) {
        this.receivedBatchId = batchId;
    }
}
