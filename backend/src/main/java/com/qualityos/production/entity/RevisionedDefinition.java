package com.qualityos.production.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A BOM, recipe or routing revision. Its content changes only as a draft; approval freezes a snapshot and dates when it
 * takes effect. See docs/MANUFACTURING.md.
 */
@Getter
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class RevisionedDefinition extends TenantOwnedEntity {

    public enum Status { DRAFT, SUBMITTED, APPROVED, OBSOLETE }

    private UUID productId;
    private int revision;

    @Enumerated(EnumType.STRING)
    private Status status = Status.DRAFT;

    @Setter
    private String changeNote;

    private String reviewNote;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private UUID submittedBy;
    private Instant submittedAt;
    private UUID approvedBy;
    private Instant approvedAt;
    private Instant obsoletedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    private String snapshot;

    protected RevisionedDefinition(UUID productId, int revision, String changeNote) {
        this.productId = productId;
        this.revision = revision;
        this.changeNote = changeNote;
    }

    public void requireDraft() {
        if (status != Status.DRAFT) {
            throw new BusinessRuleException("Revision %d is %s, so it cannot be changed. Start a new revision instead."
                    .formatted(revision, status.name().toLowerCase()));
        }
    }

    public void submit(UUID actor, Instant now) {
        requireDraft();
        status = Status.SUBMITTED;
        submittedBy = actor;
        submittedAt = now;
    }

    public void sendBack(String note) {
        requireStatus(Status.SUBMITTED, "sent back");
        status = Status.DRAFT;
        reviewNote = note;
    }

    public void approve(UUID actor, LocalDate from, String frozen, Instant now) {
        requireStatus(Status.SUBMITTED, "approved");
        status = Status.APPROVED;
        approvedBy = actor;
        approvedAt = now;
        effectiveFrom = from;
        snapshot = frozen;
        reviewNote = null;
    }

    /** A newer revision takes over on {@code from}, so this one's last day is the day before. */
    public void endBefore(LocalDate from) {
        effectiveTo = from.minusDays(1);
    }

    public void retire(Instant now) {
        requireStatus(Status.APPROVED, "retired");
        status = Status.OBSOLETE;
        obsoletedAt = now;
    }

    public boolean inEffectOn(LocalDate date) {
        return status == Status.APPROVED && !effectiveFrom.isAfter(date)
                && (effectiveTo == null || !effectiveTo.isBefore(date));
    }

    private void requireStatus(Status required, String action) {
        if (status != required) {
            throw new BusinessRuleException("Only a %s revision can be %s. Revision %d is %s.".formatted(
                    required.name().toLowerCase(), action, revision, status.name().toLowerCase()));
        }
    }
}
