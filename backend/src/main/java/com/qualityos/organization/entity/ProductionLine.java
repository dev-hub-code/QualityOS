package com.qualityos.organization.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "production_lines")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductionLine extends FacilityUnit {

    public ProductionLine(UUID facilityId, String code, String name) {
        super(facilityId, code, name);
    }
}
