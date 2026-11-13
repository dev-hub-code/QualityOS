package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** An inspection started for a gate. Never changed. */
@Getter
@Entity
@Immutable
@Table(name = "quality_gate_inspections")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QualityGateInspection extends TenantOwnedEntity {

    private UUID gateId;
    private UUID inspectionId;

    public QualityGateInspection(UUID gateId, UUID inspectionId) {
        this.gateId = gateId;
        this.inspectionId = inspectionId;
    }
}
