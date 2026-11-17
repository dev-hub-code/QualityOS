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

    /** The line's approved substitutes, as its BOM or recipe stood on release. Frozen, like the rest of the line. */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private java.util.List<UUID> alternateMaterialIds = new java.util.ArrayList<>();

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

    public WorkOrderMaterial withAlternates(java.util.List<UUID> alternates) {
        this.alternateMaterialIds = new java.util.ArrayList<>(alternates);
        return this;
    }

    /** Whether a lot of this material can be taken for the line: its own material, or an approved substitute. */
    public boolean takes(UUID material) {
        return material != null && (material.equals(materialId) || alternateMaterialIds.contains(material));
    }
}
