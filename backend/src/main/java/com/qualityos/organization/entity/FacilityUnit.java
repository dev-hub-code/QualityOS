package com.qualityos.organization.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A named part of a facility. */
@Getter
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class FacilityUnit extends TenantOwnedEntity {

    private UUID facilityId;
    private String code;
    private String name;

    @Setter
    private Instant archivedAt;

    protected FacilityUnit(UUID facilityId, String code, String name) {
        this.facilityId = facilityId;
        this.code = code;
        this.name = name;
    }
}
