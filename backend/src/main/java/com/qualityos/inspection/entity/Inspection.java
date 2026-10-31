package com.qualityos.inspection.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Evaluation (what the measurements say) and disposition (what happens to the material) are separate decisions,
 * held in separate fields, and made by different steps of this workflow.
 */
@Getter
@Entity
@Table(name = "inspections")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inspection extends TenantOwnedEntity {

    public enum Status { PENDING, IN_PROGRESS, SUBMITTED, APPROVED, COMPLETED, CANCELLED }

    public enum Evaluation { PASS, FAIL, PENDING_REVIEW }

    public enum Result { PASS, FAIL }

    public enum Disposition {
        ACCEPT, REJECT, HOLD, REWORK, CONDITIONAL_ACCEPTANCE;

        /** A hold keeps the material waiting for a further decision. */
        public boolean isFinal() {
            return this != HOLD;
        }
    }

    private String inspectionNumber;

    @Enumerated(EnumType.STRING)
    private InspectionTemplate.Type inspectionType;

    private UUID templateVersionId;

    /** The specification as it stood when the inspection began. Stored as written, and never rewritten. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String specSnapshot;

    private UUID receiptItemId;
    private UUID productionBatchId;
    private UUID supplierId;
    private UUID facilityId;
    private UUID materialId;
    private String batchNumber;
    private BigDecimal quantity;
    private int sampleSize;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    @Enumerated(EnumType.STRING)
    private Evaluation evaluatedResult;

    @Enumerated(EnumType.STRING)
    private Result finalResult;

    @Enumerated(EnumType.STRING)
    private Disposition disposition;

    private UUID submittedBy;
    private Instant submittedAt;
    private UUID approvedBy;
    private Instant approvedAt;

    public Inspection(
            String inspectionNumber,
            InspectionTemplate.Type inspectionType,
            UUID templateVersionId,
            String specSnapshot,
            UUID receiptItemId,
            UUID productionBatchId,
            UUID supplierId,
            UUID facilityId,
            UUID materialId,
            String batchNumber,
            BigDecimal quantity,
            int sampleSize) {
        this.inspectionNumber = inspectionNumber;
        this.inspectionType = inspectionType;
        this.templateVersionId = templateVersionId;
        this.specSnapshot = specSnapshot;
        this.receiptItemId = receiptItemId;
        this.productionBatchId = productionBatchId;
        this.supplierId = supplierId;
        this.facilityId = facilityId;
        this.materialId = materialId;
        this.batchNumber = batchNumber;
        this.quantity = quantity;
        this.sampleSize = sampleSize;
    }

    public boolean isOpenForResults() {
        return status == Status.PENDING || status == Status.IN_PROGRESS;
    }

    public void recordingResults() {
        if (!isOpenForResults()) {
            throw new BusinessRuleException("Results cannot be changed after submission.");
        }
        status = Status.IN_PROGRESS;
    }

    public void cancel() {
        if (status != Status.PENDING) {
            throw new BusinessRuleException("An inspection with results cannot be cancelled.");
        }
        status = Status.CANCELLED;
    }

    /** @param criticalFailure holds the material straight away, without waiting for anyone to notice */
    public void submit(Evaluation evaluation, boolean criticalFailure, UUID actor) {
        if (!isOpenForResults()) {
            throw new BusinessRuleException("Only an inspection in progress can be submitted.");
        }
        status = Status.SUBMITTED;
        evaluatedResult = evaluation;
        submittedBy = actor;
        submittedAt = Instant.now();
        if (criticalFailure) {
            disposition = Disposition.HOLD;
        }
    }

    /**
     * @param reviewedResult the reviewer's conclusion, needed only when the engine could not decide. It cannot
     *     overturn a failed evaluation: failed material is released through a conditional acceptance, on record.
     */
    public void approve(Result reviewedResult, UUID actor) {
        requireIndependentReview(actor);
        Result decided = switch (evaluatedResult) {
            case PASS -> Result.PASS;
            case FAIL -> Result.FAIL;
            case PENDING_REVIEW -> {
                if (reviewedResult == null) {
                    throw new BusinessRuleException("Some results need review. Choose pass or fail.");
                }
                yield reviewedResult;
            }
        };
        finalResult = decided;
        status = Status.APPROVED;
        approvedBy = actor;
        approvedAt = Instant.now();
    }

    public void sendBack(UUID actor) {
        requireIndependentReview(actor);
        status = Status.IN_PROGRESS;
        evaluatedResult = null;
        disposition = null;
        submittedBy = null;
        submittedAt = null;
    }

    public void dispose(Disposition decision) {
        if (status != Status.APPROVED) {
            throw new BusinessRuleException("Disposition follows approval of the inspection.");
        }
        if (decision == Disposition.ACCEPT && finalResult != Result.PASS) {
            throw new BusinessRuleException(
                    "Failed material cannot be accepted. Use conditional acceptance, reject, rework or hold.");
        }
        disposition = decision;
        if (decision.isFinal()) {
            status = Status.COMPLETED;
        }
    }

    private void requireIndependentReview(UUID actor) {
        if (status != Status.SUBMITTED) {
            throw new BusinessRuleException("Only a submitted inspection can be reviewed.");
        }
        if (actor.equals(submittedBy)) {
            throw new BusinessRuleException("An inspection must be reviewed by someone other than its inspector.");
        }
    }
}
