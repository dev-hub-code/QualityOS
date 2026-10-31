package com.qualityos.organization.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "facilities")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Facility extends TenantOwnedEntity {

    private String code;
    private String name;
    private String address;
    private String city;
    private String state;
    private String postalCode;
    private String gstin;
    private Instant archivedAt;

    public Facility(String code) {
        this.code = code;
    }
}
