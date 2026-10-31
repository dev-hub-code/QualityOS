package com.qualityos.supplier.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "suppliers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Supplier extends TenantOwnedEntity {

    public enum Status { DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, BLOCKED }

    private static final Map<Status, Set<Status>> ALLOWED_TRANSITIONS = Map.of(
            Status.DRAFT, Set.of(Status.PENDING_APPROVAL),
            Status.PENDING_APPROVAL, Set.of(Status.APPROVED, Status.REJECTED),
            Status.REJECTED, Set.of(Status.PENDING_APPROVAL),
            Status.APPROVED, Set.of(Status.BLOCKED),
            Status.BLOCKED, Set.of(Status.APPROVED));

    private String code;
    private String name;
    private String legalName;
    private String gstin;
    private UUID categoryId;
    private String email;
    private String phone;
    private String address;
    private String city;
    private String state;
    private String postalCode;
    private String countryCode = "IN";
    private Instant archivedAt;

    @Setter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    private Status status = Status.DRAFT;

    @Setter(AccessLevel.NONE)
    private String statusNote;

    @Setter(AccessLevel.NONE)
    private UUID approvedBy;

    @Setter(AccessLevel.NONE)
    private Instant approvedAt;

    public Supplier(String code) {
        this.code = code;
    }

    /** The only way to change status, so that a supplier cannot skip the approval workflow. */
    public void transitionTo(Status target, UUID actor, String note) {
        if (!ALLOWED_TRANSITIONS.getOrDefault(status, Set.of()).contains(target)) {
            throw new BusinessRuleException("A supplier in status %s cannot move to %s.".formatted(status, target));
        }
        status = target;
        statusNote = note;
        if (target == Status.APPROVED) {
            approvedBy = actor;
            approvedAt = Instant.now();
        }
    }
}
