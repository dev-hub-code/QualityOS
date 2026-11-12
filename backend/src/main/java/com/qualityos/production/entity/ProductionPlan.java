package com.qualityos.production.entity;

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

/** What a facility means to make over a period. Work orders are raised from its lines once it is approved. */
@Getter
@Entity
@Table(name = "production_plans")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductionPlan extends TenantOwnedEntity {

    public enum Status { DRAFT, APPROVED, CLOSED, CANCELLED }

    private String planNumber;

    @Setter
    private String name;

    private UUID facilityId;

    @Setter
    private LocalDate periodStart;

    @Setter
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    private Status status = Status.DRAFT;

    @Setter
    private String notes;

    private UUID approvedBy;
    private Instant approvedAt;
    private String statusReason;

    public ProductionPlan(String planNumber, UUID facilityId) {
        this.planNumber = planNumber;
        this.facilityId = facilityId;
    }

    public void requireDraft() {
        require(Status.DRAFT, "changed");
    }

    public void approve(UUID actor, Instant now) {
        require(Status.DRAFT, "approved");
        status = Status.APPROVED;
        approvedBy = actor;
        approvedAt = now;
    }

    public void close(String reason) {
        require(Status.APPROVED, "closed");
        status = Status.CLOSED;
        statusReason = reason;
    }

    public void cancel(String reason) {
        if (status != Status.DRAFT && status != Status.APPROVED) {
            throw new BusinessRuleException("Plan %s is %s, so it cannot be cancelled.".formatted(
                    planNumber, status.name().toLowerCase()));
        }
        status = Status.CANCELLED;
        statusReason = reason;
    }

    private void require(Status required, String action) {
        if (status != required) {
            throw new BusinessRuleException("Only a %s plan can be %s. Plan %s is %s.".formatted(
                    required.name().toLowerCase(), action, planNumber, status.name().toLowerCase()));
        }
    }
}
