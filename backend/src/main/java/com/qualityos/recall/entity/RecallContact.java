package com.qualityos.recall.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One customer's progress through a recall. Its attempts and acknowledgements are the history behind the status. */
@Getter
@Entity
@Table(name = "recall_contact_records")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecallContact extends TenantOwnedEntity {

    public enum Status {
        PENDING, CONTACT_ATTEMPTED, CONTACTED, ACKNOWLEDGED, ACTION_REQUIRED, RETURN_IN_PROGRESS, RESOLVED, UNREACHABLE
    }

    private static final Set<Status> NOT_YET_REACHED = Set.of(Status.PENDING, Status.CONTACT_ATTEMPTED, Status.UNREACHABLE);

    private UUID recallId;
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    @Setter
    private String contactPerson;
    @Setter
    private String contactDetails;
    @Setter
    private UUID assignedTo;
    @Setter
    private LocalDate followUpOn;
    @Setter
    private String notes;

    private String exceptionReason;
    private UUID exceptionBy;
    private Instant exceptionAt;

    public RecallContact(UUID recallId, UUID customerId, String contactPerson, String contactDetails) {
        this.recallId = recallId;
        this.customerId = customerId;
        this.contactPerson = contactPerson;
        this.contactDetails = contactDetails;
    }

    /** An attempt says something was tried, and only a person reached moves a customer on to contacted. */
    public void attempted(RecallContactAttempt.Outcome outcome) {
        if (NOT_YET_REACHED.contains(status)) {
            status = outcome == RecallContactAttempt.Outcome.REACHED ? Status.CONTACTED : Status.CONTACT_ATTEMPTED;
        }
    }

    public void acknowledged() {
        if (status != Status.RESOLVED) {
            status = Status.ACKNOWLEDGED;
        }
    }

    public void moveTo(Status next, long attempts, boolean acknowledged) {
        switch (next) {
            case ACTION_REQUIRED, RETURN_IN_PROGRESS -> {
                if (!acknowledged && status != Status.CONTACTED) {
                    throw new BusinessRuleException("Reach this customer before recording what happens next.");
                }
            }
            case RESOLVED -> {
                if (!acknowledged) {
                    throw new BusinessRuleException("A customer must acknowledge the recall before it can be resolved.");
                }
            }
            case UNREACHABLE -> {
                if (attempts == 0) {
                    throw new BusinessRuleException("Record a contact attempt before marking a customer unreachable.");
                }
            }
            default -> throw new BusinessRuleException("That status comes from recording an attempt or acknowledgement.");
        }
        status = next;
    }

    public void recordException(String reason, UUID approver, Instant now) {
        if (status != Status.UNREACHABLE) {
            throw new BusinessRuleException("An exception is for a customer who could not be reached.");
        }
        exceptionReason = reason;
        exceptionBy = approver;
        exceptionAt = now;
    }

    public boolean isSettled() {
        return status == Status.RESOLVED || exceptionAt != null;
    }
}
