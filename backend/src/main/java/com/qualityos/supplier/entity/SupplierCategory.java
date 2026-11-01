package com.qualityos.supplier.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Entity
@Table(name = "supplier_categories")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupplierCategory extends TenantOwnedEntity {

    @Setter
    private String name;

    public SupplierCategory(String name) {
        this.name = name;
    }
}
