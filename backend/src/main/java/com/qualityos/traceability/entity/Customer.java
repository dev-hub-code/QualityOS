package com.qualityos.traceability.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Entity
@Table(name = "customers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Customer extends TenantOwnedEntity {

    private String code;
    private String name;
    private String gstin;
    private String contactPerson;
    private String email;
    private String phone;
    private String address;

    @Setter
    private Instant archivedAt;

    public Customer(
            String code, String name, String gstin, String contactPerson, String email, String phone, String address) {
        this.code = code;
        this.name = name;
        this.gstin = gstin;
        this.contactPerson = contactPerson;
        this.email = email;
        this.phone = phone;
        this.address = address;
    }
}
