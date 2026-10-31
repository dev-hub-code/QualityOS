package com.qualityos.quality.entity;

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
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "corrective_actions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CorrectiveAction extends TenantOwnedEntity {

    public enum Status {
        OPEN, ROOT_CAUSE_ANALYSIS, ACTION_PLANNING, IMPLEMENTATION, EFFECTIVENESS_VERIFICATION, CLOSED, CANCELLED;

        public boolean isFinished() {
            return this == CLOSED || this == CANCELLED;
        }
    }

    private String capaNumber;

    @Enumerated(EnumType.STRING)
    private Status status = Status.OPEN;

    private UUID ncrId;
    private String title;
    private String problemStatement;
    private String rootCause;

    @JdbcTypeCode(SqlTypes.JSON)
    private String fiveWhys = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    private String fishbone = "{}";

    private UUID ownerId;
    private LocalDate targetDate;
    private boolean effectivenessRequired;

    @Setter
    private Instant overdueNotifiedAt;

    @Setter
    private Instant dueRemindedAt;

    private String outcomeNote;
    private Instant closedAt;
    private UUID closedBy;

    @Setter
    private UUID facilityId;

    public CorrectiveAction(String capaNumber, UUID ncrId, String title, String problemStatement, UUID ownerId,
            LocalDate targetDate, boolean effectivenessRequired) {
        this.capaNumber = capaNumber;
        this.ncrId = ncrId;
        this.title = title;
        this.problemStatement = problemStatement;
        this.ownerId = ownerId;
        this.targetDate = targetDate;
        this.effectivenessRequired = effectivenessRequired;
    }

    public void startAnalysis() {
        require(Status.OPEN, "start analysis");
        status = Status.ROOT_CAUSE_ANALYSIS;
    }

    public void saveAnalysis(String cause, String whys, String diagram) {
        require(Status.ROOT_CAUSE_ANALYSIS, "change its analysis");
        rootCause = cause;
        fiveWhys = whys;
        fishbone = diagram;
    }

    public void finishAnalysis() {
        require(Status.ROOT_CAUSE_ANALYSIS, "finish analysis");
        if (rootCause == null || rootCause.isBlank()) {
            throw new BusinessRuleException("Record the root cause first.");
        }
        status = Status.ACTION_PLANNING;
    }

    public void requirePlanning() {
        require(Status.ACTION_PLANNING, "take new actions");
    }

    public void startImplementation() {
        require(Status.ACTION_PLANNING, "start implementation");
        status = Status.IMPLEMENTATION;
    }

    public void requireImplementation() {
        require(Status.IMPLEMENTATION, "complete actions");
    }

    public void submitForVerification() {
        require(Status.IMPLEMENTATION, "go to verification");
        status = Status.EFFECTIVENESS_VERIFICATION;
    }

    /** The people who did the work cannot be the ones to say that it worked. */
    public void requireVerifiableBy(UUID actor) {
        require(Status.EFFECTIVENESS_VERIFICATION, "be verified");
        if (actor.equals(ownerId)) {
            throw new BusinessRuleException("Someone other than the CAPA owner must verify this.");
        }
    }

    /** An ineffective fix goes back to planning, keeping the actions already taken on record. */
    public void returnToPlanning() {
        require(Status.EFFECTIVENESS_VERIFICATION, "return to planning");
        status = Status.ACTION_PLANNING;
    }

    public void close(UUID actor, String note, Instant now) {
        requireVerifiableBy(actor);
        closedBy = actor;
        closedAt = now;
        outcomeNote = note;
        status = Status.CLOSED;
    }

    public void cancel(String reason) {
        if (status.isFinished()) {
            throw new BusinessRuleException("A CAPA in status " + status + " cannot be cancelled.");
        }
        outcomeNote = reason;
        status = Status.CANCELLED;
    }

    private void require(Status allowed, String action) {
        if (status != allowed) {
            throw new BusinessRuleException("A CAPA in status " + status + " cannot " + action + ".");
        }
    }
}
