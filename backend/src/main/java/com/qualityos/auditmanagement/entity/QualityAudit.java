package com.qualityos.auditmanagement.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Status changes only through these methods, each of which states where it may start from. */
@Getter
@Entity
@Table(name = "audits")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QualityAudit extends TenantOwnedEntity {

    public enum Type { INTERNAL, SUPPLIER, PROCESS, PRODUCT }

    public enum Status { PLANNED, IN_PROGRESS, COMPLETED, PENDING_CLOSURE, CLOSED, CANCELLED }

    private String auditNumber;

    @Enumerated(EnumType.STRING)
    private Type auditType;

    private String title;
    private String scope;
    private UUID facilityId;
    private UUID supplierId;
    private UUID productId;
    private UUID checklistId;
    private LocalDate plannedOn;
    private UUID leadAuditorId;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PLANNED;

    private String summary;
    private Instant startedAt;
    private Instant completedAt;
    private UUID closureRequestedBy;
    private Instant closureRequestedAt;
    private String closureNote;
    private UUID closedBy;
    private Instant closedAt;
    private String cancelReason;

    /** Empty for a one-off audit. */
    private Integer repeatEveryMonths;

    /** The audit planned when this one closed, if it repeats. */
    private UUID nextAuditId;

    private Instant reminderSentAt;

    public QualityAudit(String auditNumber, Type auditType, String title, String scope, UUID facilityId,
            UUID supplierId, UUID productId, UUID checklistId, LocalDate plannedOn, UUID leadAuditorId,
            Integer repeatEveryMonths) {
        this.auditNumber = auditNumber;
        this.auditType = auditType;
        this.title = title;
        this.scope = scope;
        this.facilityId = facilityId;
        this.supplierId = supplierId;
        this.productId = productId;
        this.checklistId = checklistId;
        this.plannedOn = plannedOn;
        this.leadAuditorId = leadAuditorId;
        this.repeatEveryMonths = repeatEveryMonths;
    }

    /** A new date or a new lead auditor earns a fresh reminder. */
    public void replan(String title, String scope, LocalDate plannedOn, UUID leadAuditorId, Integer repeatEveryMonths) {
        require(Status.PLANNED, "Only a planned audit can be changed.");
        if (!plannedOn.equals(this.plannedOn) || !leadAuditorId.equals(this.leadAuditorId)) {
            reminderSentAt = null;
        }
        this.title = title;
        this.scope = scope;
        this.plannedOn = plannedOn;
        this.leadAuditorId = leadAuditorId;
        this.repeatEveryMonths = repeatEveryMonths;
    }

    public void reminded(Instant now) {
        reminderSentAt = now;
    }

    public void followedBy(UUID next) {
        nextAuditId = next;
    }

    /** The first date in the repeat cycle that is not before {@code today}. */
    public LocalDate nextPlannedOn(LocalDate today) {
        LocalDate next = plannedOn.plusMonths(repeatEveryMonths);
        while (next.isBefore(today)) {
            next = next.plusMonths(repeatEveryMonths);
        }
        return next;
    }

    public void start(Instant now) {
        require(Status.PLANNED, "This audit has already started.");
        status = Status.IN_PROGRESS;
        startedAt = now;
    }

    public void requireInProgress() {
        require(Status.IN_PROGRESS, "This audit is not in progress.");
    }

    public void complete(String summary, Instant now) {
        requireInProgress();
        this.summary = summary;
        status = Status.COMPLETED;
        completedAt = now;
    }

    public void requestClosure(UUID requester, String note, Instant now) {
        require(Status.COMPLETED, "Only a completed audit can go for closure.");
        status = Status.PENDING_CLOSURE;
        closureRequestedBy = requester;
        closureRequestedAt = now;
        closureNote = note;
    }

    public void sendBack() {
        require(Status.PENDING_CLOSURE, "This audit is not waiting for closure.");
        status = Status.COMPLETED;
        closureRequestedBy = null;
        closureRequestedAt = null;
        closureNote = null;
    }

    /** @param reviewPermitted whether the approver may approve, which a requester may only in a one-person organisation */
    public void close(UUID approver, Instant now, boolean reviewPermitted) {
        require(Status.PENDING_CLOSURE, "This audit is not waiting for closure.");
        if (!reviewPermitted) {
            throw new BusinessRuleException("Someone else must approve closing this audit.");
        }
        status = Status.CLOSED;
        closedBy = approver;
        closedAt = now;
    }

    public void cancel(String reason) {
        if (status != Status.PLANNED && status != Status.IN_PROGRESS) {
            throw new BusinessRuleException("Only an audit that has not been completed can be cancelled.");
        }
        status = Status.CANCELLED;
        cancelReason = reason;
    }

    public boolean isFinished() {
        return status == Status.CLOSED || status == Status.CANCELLED;
    }

    private void require(Status expected, String message) {
        if (status != expected) {
            throw new BusinessRuleException(message);
        }
    }
}
