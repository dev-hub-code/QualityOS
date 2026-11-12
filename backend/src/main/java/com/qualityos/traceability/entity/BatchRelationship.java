package com.qualityos.traceability.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One thing that went into a batch. Append-only: the database refuses any change. */
@Getter
@Entity
@Table(name = "batch_relationships")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BatchRelationship extends TenantOwnedEntity {

    private UUID parentLotId;
    private UUID parentBatchId;
    private UUID childBatchId;
    /** In the source's own unit. */
    private BigDecimal quantity;
    private BigDecimal enteredQuantity;
    private UUID enteredUomId;

    public BatchRelationship(UUID parentLotId, UUID parentBatchId, UUID childBatchId, BigDecimal quantity) {
        this(parentLotId, parentBatchId, childBatchId, quantity, null, null);
    }

    /** @param enteredQuantity what was typed, in {@code enteredUomId}, before it became {@code quantity} */
    public BatchRelationship(UUID parentLotId, UUID parentBatchId, UUID childBatchId, BigDecimal quantity,
            BigDecimal enteredQuantity, UUID enteredUomId) {
        this.parentLotId = parentLotId;
        this.parentBatchId = parentBatchId;
        this.childBatchId = childBatchId;
        this.quantity = quantity;
        this.enteredQuantity = enteredQuantity;
        this.enteredUomId = enteredUomId;
    }
}
