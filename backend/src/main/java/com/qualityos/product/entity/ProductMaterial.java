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
@Table(name = "product_materials")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductMaterial extends TenantOwnedEntity {

    private UUID productId;
    private UUID materialId;
    private BigDecimal quantityPerUnit;
    private UUID uomId;

    public ProductMaterial(UUID productId, UUID materialId, BigDecimal quantityPerUnit, UUID uomId) {
        this.productId = productId;
        this.materialId = materialId;
        this.quantityPerUnit = quantityPerUnit;
        this.uomId = uomId;
    }
}
