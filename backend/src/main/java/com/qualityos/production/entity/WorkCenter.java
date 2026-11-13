package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "work_centers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkCenter extends TenantOwnedEntity {

    private UUID facilityId;
    private UUID productionLineId;
    private String code;
    private String name;
    private String description;
    private Instant archivedAt;
    /** Operations run side by side when the centre has no machines; with machines, each active one counts. */
    private int capacityUnits = 1;
    /** Per hour of running time, in the organisation's currency; null when not costed. */
    private BigDecimal labourRate;
    private BigDecimal machineRate;
    private BigDecimal overheadRate;

    public WorkCenter(UUID facilityId, String code) {
        this.facilityId = facilityId;
        this.code = code;
    }
}
