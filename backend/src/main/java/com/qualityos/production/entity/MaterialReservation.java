package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Stock on one lot set aside for one material line of a work order, in the lot's unit. The lot holds the same
 * quantity as reserved, so nothing else can take it. Issuing or releasing brings the open quantity down; the database
 * refuses anything else.
 */
@Getter
@Entity
@Table(name = "material_reservations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MaterialReservation extends TenantOwnedEntity {

    private UUID workOrderId;
    private UUID workOrderMaterialId;
    private UUID lotId;
    private BigDecimal quantity;
    private BigDecimal openQuantity;
    private UUID uomId;

    public MaterialReservation(UUID workOrderId, UUID workOrderMaterialId, UUID lotId, BigDecimal quantity, UUID uomId) {
        this.workOrderId = workOrderId;
        this.workOrderMaterialId = workOrderMaterialId;
        this.lotId = lotId;
        this.quantity = quantity;
        this.openQuantity = quantity;
        this.uomId = uomId;
    }

    /** @return how much of {@code amount} this reservation covered */
    public BigDecimal use(BigDecimal amount) {
        BigDecimal used = amount.min(openQuantity);
        openQuantity = openQuantity.subtract(used);
        return used;
    }

    /** @return what was still set aside */
    public BigDecimal close() {
        BigDecimal released = openQuantity;
        openQuantity = BigDecimal.ZERO.setScale(released.scale());
        return released;
    }
}
