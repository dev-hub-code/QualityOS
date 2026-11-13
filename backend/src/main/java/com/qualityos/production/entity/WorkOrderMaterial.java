package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One component a released work order needs, in the component's own unit. The database refuses a rewrite. */
@Getter
@Entity
@Table(name = "work_order_materials")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkOrderMaterial extends TenantOwnedEntity {

    private UUID workOrderId;
    private int lineNumber;
    private UUID materialId;
    private UUID componentProductId;
    private BigDecimal requiredQuantity;
    private UUID uomId;

    public WorkOrderMaterial(
            UUID workOrderId, int lineNumber, UUID materialId, UUID componentProductId, BigDecimal requiredQuantity,
            UUID uomId) {
        this.workOrderId = workOrderId;
        this.lineNumber = lineNumber;
        this.materialId = materialId;
        this.componentProductId = componentProductId;
        this.requiredQuantity = requiredQuantity;
        this.uomId = uomId;
    }
}
