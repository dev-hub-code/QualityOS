package com.qualityos.traceability.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The database refuses any change once a correction is applied, rejected or cancelled. */
@Getter
@Entity
@Table(name = "material_corrections")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MaterialCorrection extends TenantOwnedEntity {

    public enum TargetType { LOT, CONSUMPTION }

    public enum Status { DRAFT, PENDING_APPROVAL, APPROVED, APPLIED, REJECTED, CANCELLED }

    private String correctionNumber;

    @Enumerated(EnumType.STRING)
    private TargetType targetType;

    private UUID receiptItemId;
    private UUID batchRelationshipId;
    private UUID productionBatchId;

    @Setter
    private UUID facilityId;

    @Enumerated(EnumType.STRING)
    private Status status = Status.DRAFT;

    private String reason;
    private boolean additionalApproval;
    private Instant submittedAt;
    private String decisionNote;
    private Instant decidedAt;
    private Instant appliedAt;
    private UUID replacementRelationshipId;

    public MaterialCorrection(
            String correctionNumber,
            TargetType targetType,
            UUID receiptItemId,
            UUID batchRelationshipId,
            UUID productionBatchId,
            String reason) {
        this.correctionNumber = correctionNumber;
        this.targetType = targetType;
        this.receiptItemId = receiptItemId;
        this.batchRelationshipId = batchRelationshipId;
        this.productionBatchId = productionBatchId;
        this.reason = reason;
    }

    public int approvalsNeeded() {
        return additionalApproval ? 2 : 1;
    }

    public void submit(boolean additionalApproval, Instant now) {
        require(Status.DRAFT, "Only a draft can be submitted.");
        this.additionalApproval = additionalApproval;
        status = Status.PENDING_APPROVAL;
        submittedAt = now;
    }

    /** Downstream use can appear after submission, and then the higher bar applies. */
    public void requireAdditionalApproval() {
        additionalApproval = true;
    }

    public void approved(Instant now) {
        require(Status.PENDING_APPROVAL, "Only a correction awaiting approval can be approved.");
        status = Status.APPROVED;
        decidedAt = now;
    }

    public void applied(UUID replacementRelationshipId, Instant now) {
        require(Status.APPROVED, "Only an approved correction can be applied.");
        this.replacementRelationshipId = replacementRelationshipId;
        status = Status.APPLIED;
        appliedAt = now;
    }

    public void reject(String note, Instant now) {
        require(Status.PENDING_APPROVAL, "Only a correction awaiting approval can be rejected.");
        status = Status.REJECTED;
        decisionNote = note;
        decidedAt = now;
    }

    public void cancel(String note) {
        if (status != Status.DRAFT && status != Status.PENDING_APPROVAL) {
            throw new BusinessRuleException("This correction can no longer be cancelled.");
        }
        status = Status.CANCELLED;
        decisionNote = note;
    }

    private void require(Status expected, String message) {
        if (status != expected) {
            throw new BusinessRuleException(message);
        }
    }
}
