package com.qualityos.traceability.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Append-only: the database refuses any change. */
@Getter
@Entity
@Table(name = "dispatch_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DispatchItem extends TenantOwnedEntity {

    private UUID dispatchId;
    private UUID productionBatchId;
    private BigDecimal quantity;

    public DispatchItem(UUID dispatchId, UUID productionBatchId, BigDecimal quantity) {
        this.dispatchId = dispatchId;
        this.productionBatchId = productionBatchId;
        this.quantity = quantity;
    }
}
