package com.qualityos.product.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "product_specifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductSpecification extends TenantOwnedEntity {

    private UUID productId;
    private String name;
    private BigDecimal nominalValue;
    private BigDecimal minValue;
    private BigDecimal maxValue;
    private String textValue;
    private UUID uomId;
    private String notes;
    private int sortOrder;

    public ProductSpecification(
            UUID productId,
            String name,
            BigDecimal nominalValue,
            BigDecimal minValue,
            BigDecimal maxValue,
            String textValue,
            UUID uomId,
            String notes,
            int sortOrder) {
        this.productId = productId;
        this.name = name;
        this.nominalValue = nominalValue;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.textValue = textValue;
        this.uomId = uomId;
        this.notes = notes;
        this.sortOrder = sortOrder;
    }
}
