package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** A checkpoint as a work order took it on release. Never changed: where it stands comes from its inspections. */
@Getter
@Entity
@Immutable
@Table(name = "work_order_quality_gates")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QualityGate extends TenantOwnedEntity {

    private UUID workOrderId;
    private UUID operationId;
    private UUID checkpointId;
    private String name;

    @Enumerated(EnumType.STRING)
    private QualityCheckpoint.Stage stage;

    private UUID templateId;
    private boolean mandatory;

    public QualityGate(UUID workOrderId, UUID operationId, QualityCheckpoint checkpoint) {
        this.workOrderId = workOrderId;
        this.operationId = operationId;
        this.checkpointId = checkpoint.getId();
        this.name = checkpoint.getName();
        this.stage = checkpoint.getStage();
        this.templateId = checkpoint.getTemplateId();
        this.mandatory = checkpoint.isMandatory();
    }
}
