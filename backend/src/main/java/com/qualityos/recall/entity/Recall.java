package com.qualityos.recall.entity;

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

@Getter
@Entity
@Table(name = "recalls")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recall extends TenantOwnedEntity {

    public enum Status { OPEN, PENDING_CLOSURE, CLOSED }

    /** As regulators grade recalls: I may cause serious harm or death, II temporary harm, III is unlikely to harm. */
    public enum HazardClass { CLASS_I, CLASS_II, CLASS_III }

    private String recallNumber;
    private LocalDate recallDate;
    private String reason;
    private String instructions;
    private UUID capaId;

    @Enumerated(EnumType.STRING)
    private Status status = Status.OPEN;

    private UUID closureRequestedBy;
    private Instant closureRequestedAt;
    private String closureNote;
    private UUID closedBy;
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    private HazardClass hazardClass;
    private String regulator;
    private String regulatorReference;
    private LocalDate regulatorNotifiedOn;
    private int responseDays = 3;

    public Recall(String recallNumber, LocalDate recallDate, String reason, String instructions, UUID capaId) {
        this.recallNumber = recallNumber;
        this.recallDate = recallDate;
        this.reason = reason;
        this.instructions = instructions;
        this.capaId = capaId;
    }

    /** What a regulator is told, which may arrive or change at any point, after closure too. */
    public void describeForRegulator(HazardClass hazardClass, String regulator, String reference, LocalDate notifiedOn,
            int responseDays) {
        if (notifiedOn != null && notifiedOn.isBefore(recallDate)) {
            throw new BusinessRuleException("The regulator cannot have been told before the recall started.");
        }
        this.hazardClass = hazardClass;
        this.regulator = regulator;
        this.regulatorReference = reference;
        this.regulatorNotifiedOn = notifiedOn;
        this.responseDays = responseDays;
    }

    public void linkCapa(UUID capaId) {
        requireOpen();
        this.capaId = capaId;
    }

    public void requireOpen() {
        if (status != Status.OPEN) {
            throw new BusinessRuleException("This recall is no longer open for changes.");
        }
    }

    public void requestClosure(UUID requester, String note, Instant now) {
        requireOpen();
        status = Status.PENDING_CLOSURE;
        closureRequestedBy = requester;
        closureRequestedAt = now;
        closureNote = note;
    }

    public void sendBack() {
        requirePendingClosure();
        status = Status.OPEN;
        closureRequestedBy = null;
        closureRequestedAt = null;
        closureNote = null;
    }

    /** @param reviewPermitted whether the approver may approve, which a requester may only in a one-person organisation */
    public void close(UUID approver, Instant now, boolean reviewPermitted) {
        requirePendingClosure();
        if (!reviewPermitted) {
            throw new BusinessRuleException("Someone else must approve closing this recall.");
        }
        status = Status.CLOSED;
        closedBy = approver;
        closedAt = now;
    }

    private void requirePendingClosure() {
        if (status != Status.PENDING_CLOSURE) {
            throw new BusinessRuleException("This recall is not waiting for closure approval.");
        }
    }
}
