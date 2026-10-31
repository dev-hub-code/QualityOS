package com.qualityos.organization.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "departments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Department extends FacilityUnit {

    public Department(UUID facilityId, String code, String name) {
        super(facilityId, code, name);
    }
}
