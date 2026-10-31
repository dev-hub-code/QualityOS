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

    public Recall(String recallNumber, LocalDate recallDate, String reason, String instructions, UUID capaId) {
        this.recallNumber = recallNumber;
        this.recallDate = recallDate;
        this.reason = reason;
        this.instructions = instructions;
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

    public void close(UUID approver, Instant now) {
        requirePendingClosure();
        if (approver.equals(closureRequestedBy)) {
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
