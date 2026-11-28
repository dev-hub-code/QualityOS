package com.qualityos.material.entity;

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

/**
 * One of a unit, for one material or product, in that item's own unit: 1 DRUM of glycerin is 200 KG, 1 LTR of it is
 * 1.26 KG. A rule never changes, so a quantity converted with it keeps its meaning; it is removed and added again.
 */
@Getter
@Entity
@Table(name = "item_unit_conversions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ItemUnitConversion extends TenantOwnedEntity {

    public enum ItemKind { MATERIAL, PRODUCT }

    @Enumerated(EnumType.STRING)
    private ItemKind itemKind;

    private UUID itemId;
    private UUID uomId;
    private BigDecimal factor;

    public ItemUnitConversion(ItemKind itemKind, UUID itemId, UUID uomId, BigDecimal factor) {
        this.itemKind = itemKind;
        this.itemId = itemId;
        this.uomId = uomId;
        this.factor = factor;
    }
}
