package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Entity
@Table(name = "product_manufacturing_settings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductManufacturingSettings extends TenantOwnedEntity {

    private UUID productId;

    @Setter
    @Enumerated(EnumType.STRING)
    private ManufacturingModel manufacturingModel;

    public ProductManufacturingSettings(UUID productId, ManufacturingModel model) {
        this.productId = productId;
        this.manufacturingModel = model;
    }
}
