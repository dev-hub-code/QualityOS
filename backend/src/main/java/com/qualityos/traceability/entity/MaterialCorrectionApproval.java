package com.qualityos.traceability.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Append-only. One person counts once. */
@Getter
@Entity
@Table(name = "material_correction_approvals")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MaterialCorrectionApproval extends TenantOwnedEntity {

    private UUID correctionId;
    private UUID approverId;
    private String note;
    private boolean impactAcknowledged;

    public MaterialCorrectionApproval(UUID correctionId, UUID approverId, String note, boolean impactAcknowledged) {
        this.correctionId = correctionId;
        this.approverId = approverId;
        this.note = note;
        this.impactAcknowledged = impactAcknowledged;
    }
}
