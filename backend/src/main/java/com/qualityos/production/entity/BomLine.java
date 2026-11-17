package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One component: a material, or a product made in its own right (a sub-assembly or intermediate). */
@Getter
@Setter
@Entity
@Table(name = "bom_lines")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BomLine extends TenantOwnedEntity {

    private UUID bomId;
    private int lineNumber;
    private UUID materialId;
    private UUID componentProductId;
    private BigDecimal quantity;
    private UUID uomId;
    private BigDecimal scrapPercent = BigDecimal.ZERO;
    private boolean scales = true;
    private String notes;

    /**
     * Materials approved to stand in for this line's material, one for one in the line's unit. They are frozen into a
     * work order on release, and reserved or issued against the line by hand.
     */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private java.util.List<UUID> alternateMaterialIds = new java.util.ArrayList<>();

    public BomLine(UUID bomId, int lineNumber) {
        this.bomId = bomId;
        this.lineNumber = lineNumber;
    }

    public UUID componentId() {
        return materialId != null ? materialId : componentProductId;
    }
}
