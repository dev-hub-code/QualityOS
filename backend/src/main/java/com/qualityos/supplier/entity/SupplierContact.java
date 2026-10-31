package com.qualityos.supplier.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "supplier_contacts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupplierContact extends TenantOwnedEntity {

    private UUID supplierId;
    private String name;
    private String designation;
    private String email;
    private String phone;

    @Column(name = "is_primary")
    private boolean primary;

    public SupplierContact(
            UUID supplierId, String name, String designation, String email, String phone, boolean primary) {
        this.supplierId = supplierId;
        this.name = name;
        this.designation = designation;
        this.email = email;
        this.phone = phone;
        this.primary = primary;
    }
}
