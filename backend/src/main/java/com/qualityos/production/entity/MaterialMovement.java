package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** One reservation, release, issue or return for a work order, in the source's unit. Never changed. */
@Getter
@Entity
@Immutable
@Table(name = "work_order_material_movements")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MaterialMovement extends TenantOwnedEntity {

    public enum Type { RESERVED, UNRESERVED, ISSUED, RETURNED }

    private UUID workOrderId;
    private UUID workOrderMaterialId;

    @Enumerated(EnumType.STRING)
    private Type movementType;

    private UUID lotId;
    private UUID sourceBatchId;
    private BigDecimal quantity;
    private UUID uomId;
    private UUID reservationId;
    private UUID batchRelationshipId;
    private String reason;
    private String requestKey;

    public MaterialMovement(
            UUID workOrderId,
            UUID workOrderMaterialId,
            Type movementType,
            UUID lotId,
            UUID sourceBatchId,
            BigDecimal quantity,
            UUID uomId,
            UUID reservationId,
            UUID batchRelationshipId,
            String reason,
            String requestKey) {
        this.workOrderId = workOrderId;
        this.workOrderMaterialId = workOrderMaterialId;
        this.movementType = movementType;
        this.lotId = lotId;
        this.sourceBatchId = sourceBatchId;
        this.quantity = quantity;
        this.uomId = uomId;
        this.reservationId = reservationId;
        this.batchRelationshipId = batchRelationshipId;
        this.reason = reason;
        this.requestKey = requestKey;
    }
}
