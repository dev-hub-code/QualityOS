package com.qualityos.traceability.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Nothing leaves a batch until a final inspection has released it. Only inspections can move it past COMPLETED. */
@Getter
@Entity
@Table(name = "production_batches")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductionBatch extends TenantOwnedEntity {

    public enum Status { IN_PROGRESS, COMPLETED, ON_HOLD, RELEASED, REJECTED }

    private static final String IN_PROCESS = "IPQC";
    private static final String FINAL = "FQC";

    private String batchNumber;
    private UUID productId;
    private UUID facilityId;

    @Enumerated(EnumType.STRING)
    private Status status = Status.IN_PROGRESS;

    private BigDecimal plannedQuantity;
    private BigDecimal producedQuantity;
    private BigDecimal usedQuantity = BigDecimal.ZERO;
    private BigDecimal dispatchedQuantity = BigDecimal.ZERO;
    private Instant completedAt;

    public ProductionBatch(String batchNumber, UUID productId, UUID facilityId, BigDecimal plannedQuantity) {
        this.batchNumber = batchNumber;
        this.productId = productId;
        this.facilityId = facilityId;
        this.plannedQuantity = plannedQuantity;
    }

    public void requireInProgress() {
        if (status != Status.IN_PROGRESS) {
            throw new BusinessRuleException("Batch " + batchNumber + " is not in progress.");
        }
    }

    public void complete(BigDecimal produced, Instant now) {
        requireInProgress();
        producedQuantity = produced;
        completedAt = now;
        status = Status.COMPLETED;
    }

    public void requireInspectable(String inspectionType) {
        if (IN_PROCESS.equals(inspectionType)) {
            // A batch held for rework is re-inspected, which is the only way it gets back on the line.
            boolean heldInProcess = status == Status.ON_HOLD && producedQuantity == null;
            if (!heldInProcess) {
                requireInProgress();
            }
        } else if (FINAL.equals(inspectionType)) {
            if (producedQuantity == null || (status != Status.COMPLETED && status != Status.ON_HOLD)) {
                throw new BusinessRuleException("Final inspection needs a completed batch.");
            }
        } else {
            throw new BusinessRuleException("A production batch takes in-process or final inspection templates.");
        }
    }

    /** @param disposition null when a hold is lifted because the inspection was sent back */
    public void applyInspectionOutcome(String inspectionType, String disposition) {
        if (disposition == null) {
            resume();
            return;
        }
        switch (disposition) {
            case "ACCEPT", "CONDITIONAL_ACCEPTANCE" -> {
                if (FINAL.equals(inspectionType)) {
                    status = Status.RELEASED;
                } else {
                    resume();
                }
            }
            case "REJECT" -> status = Status.REJECTED;
            default -> status = Status.ON_HOLD;
        }
    }

    private void resume() {
        if (status == Status.ON_HOLD) {
            status = producedQuantity == null ? Status.IN_PROGRESS : Status.COMPLETED;
        }
    }

    public BigDecimal availableQuantity() {
        return producedQuantity == null
                ? BigDecimal.ZERO
                : producedQuantity.subtract(usedQuantity).subtract(dispatchedQuantity);
    }

    /** As an ingredient of a later batch. */
    public void use(BigDecimal quantity) {
        requireAvailable(quantity);
        usedQuantity = usedQuantity.add(quantity);
    }

    public void dispatch(BigDecimal quantity) {
        requireAvailable(quantity);
        dispatchedQuantity = dispatchedQuantity.add(quantity);
    }

    /** Corrections only: returns stock taken by a consumption that was reversed. */
    public void returnUsed(BigDecimal quantity) {
        usedQuantity = usedQuantity.subtract(quantity);
    }

    /** Corrections only: returns stock taken by a dispatch that was reversed. */
    public void returnDispatched(BigDecimal quantity) {
        dispatchedQuantity = dispatchedQuantity.subtract(quantity);
    }

    private void requireAvailable(BigDecimal quantity) {
        if (status != Status.RELEASED) {
            throw new BusinessRuleException("Batch " + batchNumber + " has not been released.");
        }
        if (quantity.compareTo(availableQuantity()) > 0) {
            throw new BusinessRuleException("Batch " + batchNumber + " has only " + availableQuantity() + " available.");
        }
    }
}
