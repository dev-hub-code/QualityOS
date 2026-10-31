package com.qualityos.inspection.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One entry in an inspection's decision history. */
@Getter
@Entity
@Table(name = "inspection_approvals")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InspectionApproval extends TenantOwnedEntity {

    private UUID inspectionId;
    private String decision;
    private String note;

    public InspectionApproval(UUID inspectionId, String decision, String note) {
        this.inspectionId = inspectionId;
        this.decision = decision;
        this.note = note;
    }
}
