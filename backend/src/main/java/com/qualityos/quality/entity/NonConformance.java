package com.qualityos.quality.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Status changes only through these methods, each of which states where it may start from. */
@Getter
@Entity
@Table(name = "non_conformances")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NonConformance extends TenantOwnedEntity {

    public enum Status {
        OPEN, UNDER_INVESTIGATION, CONTAINMENT, ACTION_REQUIRED, PENDING_VERIFICATION, CLOSED, REJECTED, CANCELLED;

        public boolean isFinished() {
            return this == CLOSED || this == REJECTED || this == CANCELLED;
        }
    }

    public enum Source { INSPECTION, MANUAL, AUDIT }

    public enum Severity { CRITICAL, MAJOR, MINOR }

    private String ncrNumber;

    @Enumerated(EnumType.STRING)
    private Status status = Status.OPEN;

    @Enumerated(EnumType.STRING)
    private Source source;

    @Setter
    private UUID facilityId;

    private UUID inspectionId;
    private UUID auditId;
    private UUID auditFindingId;
    private UUID supplierId;
    private UUID materialId;
    private String batchNumber;
    private UUID defectCategoryId;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    private String title;
    private String description;
    private BigDecimal rejectedQuantity;
    private UUID assignedTo;
    private String containmentAction;
    private String rootCause;
    private UUID verificationRequestedBy;
    private String outcomeNote;
    private Instant closedAt;
    private UUID closedBy;

    /** An NCR that comes from an audit finding, which is how the audit follows its correction. */
    public static NonConformance fromAudit(String ncrNumber, UUID auditId, UUID findingId, UUID supplierId,
            Severity severity, String title, String description) {
        NonConformance ncr = new NonConformance(ncrNumber, Source.AUDIT, null, supplierId, null, null, null, severity,
                title, description, null);
        ncr.auditId = auditId;
        ncr.auditFindingId = findingId;
        return ncr;
    }

    public NonConformance(String ncrNumber, Source source, UUID inspectionId, UUID supplierId, UUID materialId,
            String batchNumber, UUID defectCategoryId, Severity severity, String title, String description,
            BigDecimal rejectedQuantity) {
        this.ncrNumber = ncrNumber;
        this.source = source;
        this.inspectionId = inspectionId;
        this.supplierId = supplierId;
        this.materialId = materialId;
        this.batchNumber = batchNumber;
        this.defectCategoryId = defectCategoryId;
        this.severity = severity;
        this.title = title;
        this.description = description;
        this.rejectedQuantity = rejectedQuantity;
    }

    /** Moves a new NCR into investigation. Reassigning later does not change the status. */
    public void assign(UUID assignee) {
        if (status.isFinished() || status == Status.PENDING_VERIFICATION) {
            throw refused("assigned");
        }
        assignedTo = assignee;
        if (status == Status.OPEN) {
            status = Status.UNDER_INVESTIGATION;
        }
    }

    public void recordContainment(String action) {
        require(Set.of(Status.UNDER_INVESTIGATION), "contained");
        containmentAction = action;
        status = Status.CONTAINMENT;
    }

    public void recordInvestigation(String cause, UUID category) {
        require(Set.of(Status.CONTAINMENT), "investigated");
        if (category != null) {
            defectCategoryId = category;
        }
        if (defectCategoryId == null) {
            throw new BusinessRuleException("Choose a defect category.");
        }
        rootCause = cause;
        status = Status.ACTION_REQUIRED;
    }

    public void requestVerification(UUID actor) {
        require(Set.of(Status.ACTION_REQUIRED), "sent for verification");
        verificationRequestedBy = actor;
        status = Status.PENDING_VERIFICATION;
    }

    public void sendBack() {
        require(Set.of(Status.PENDING_VERIFICATION), "sent back");
        verificationRequestedBy = null;
        status = Status.ACTION_REQUIRED;
    }

    public void close(UUID actor, String note, Instant now) {
        require(Set.of(Status.PENDING_VERIFICATION), "closed");
        if (actor.equals(verificationRequestedBy)) {
            throw new BusinessRuleException("Someone other than the person who requested verification must close this.");
        }
        closedBy = actor;
        closedAt = now;
        outcomeNote = note;
        status = Status.CLOSED;
    }

    /** This was not a real non-conformance. Only a reviewer can say so, and only before work has started on it. */
    public void reject(String reason) {
        require(Set.of(Status.OPEN, Status.UNDER_INVESTIGATION), "rejected");
        outcomeNote = reason;
        status = Status.REJECTED;
    }

    /** An inspection's NCR cannot be cancelled: the failure it records did happen. */
    public void cancel(String reason) {
        require(Set.of(Status.OPEN), "cancelled");
        if (source == Source.INSPECTION) {
            throw new BusinessRuleException("An NCR raised by an inspection cannot be cancelled. A reviewer can reject it.");
        }
        outcomeNote = reason;
        status = Status.CANCELLED;
    }

    private void require(Set<Status> allowed, String action) {
        if (!allowed.contains(status)) {
            throw refused(action);
        }
    }

    private BusinessRuleException refused(String action) {
        return new BusinessRuleException("An NCR in status " + status + " cannot be " + action + ".");
    }

    /** Corrections only. Finished NCRs are left as they were closed. */
    public void correctSubject(UUID supplierId, UUID materialId) {
        if (supplierId != null) {
            this.supplierId = supplierId;
        }
        if (materialId != null) {
            this.materialId = materialId;
        }
    }
}
