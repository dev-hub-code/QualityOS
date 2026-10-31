package com.qualityos.traceability.entity;

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

/** Goods coming back against a dispatch. It references the dispatch and never changes it. */
@Getter
@Entity
@Table(name = "dispatch_reversals")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DispatchReversal extends TenantOwnedEntity {

    public enum Status { EXPECTED, RETURN_RECEIVED, PENDING_INSPECTION, CLOSED, CANCELLED }

    private String reversalNumber;
    private UUID dispatchId;
    private String returnReference;
    private LocalDate reversalDate;
    private String reason;

    @Enumerated(EnumType.STRING)
    private Status status = Status.RETURN_RECEIVED;

    private Instant closedAt;

    public DispatchReversal(
            String reversalNumber,
            UUID dispatchId,
            String returnReference,
            LocalDate reversalDate,
            String reason,
            boolean expected) {
        this.reversalNumber = reversalNumber;
        this.dispatchId = dispatchId;
        this.returnReference = returnReference;
        this.reversalDate = reversalDate;
        this.reason = reason;
        this.status = expected ? Status.EXPECTED : Status.RETURN_RECEIVED;
    }

    /** The goods are here, so the date becomes the day they arrived. */
    public void received(LocalDate on) {
        status = Status.RETURN_RECEIVED;
        reversalDate = on;
    }

    public void cancel(Instant now) {
        status = Status.CANCELLED;
        closedAt = now;
    }

    public void inspectionStarted() {
        status = Status.PENDING_INSPECTION;
    }

    public void close(Instant now) {
        status = Status.CLOSED;
        closedAt = now;
    }
}
