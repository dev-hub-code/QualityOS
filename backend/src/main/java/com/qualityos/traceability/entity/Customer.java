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

    @Setter
    private Instant archivedAt;

    public Customer(String code, String name, String gstin) {
        this.code = code;
        this.name = name;
        this.gstin = gstin;
    }
}
