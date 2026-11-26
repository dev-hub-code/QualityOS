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
    private static final String OUTGOING = "OQC";

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

    /** Sent to another plant, in transit or received there as a batch of its own. */
    private BigDecimal transferredQuantity = BigDecimal.ZERO;

    /** The batch this one was sent from, for a batch received from another plant. */
    private UUID transferredFromId;

    public ProductionBatch(String batchNumber, UUID productId, UUID facilityId, BigDecimal plannedQuantity) {
        this.batchNumber = batchNumber;
        this.productId = productId;
        this.facilityId = facilityId;
        this.plannedQuantity = plannedQuantity;
    }

    /**
     * The part of {@code source} received at another plant: released, as its source was, and numbered after it. The
     * link between them is recorded as lineage too, so traces, recalls and holds reach it.
     */
    public static ProductionBatch receivedFrom(
            ProductionBatch source, String batchNumber, BigDecimal quantity, UUID facilityId, Instant now) {
        ProductionBatch batch = new ProductionBatch(batchNumber, source.productId, facilityId, quantity);
        batch.producedQuantity = quantity;
        batch.completedAt = now;
        batch.status = Status.RELEASED;
        batch.transferredFromId = source.getId();
        return batch;
    }

    /** Sends part of the batch to another plant. Only released stock can go. */
    public void transferOut(BigDecimal quantity) {
        requireAvailable(quantity);
        transferredQuantity = transferredQuantity.add(quantity);
    }

    /** A transfer cancelled while in transit: the stock is back. */
    public void returnTransferred(BigDecimal quantity) {
        transferredQuantity = transferredQuantity.subtract(quantity);
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

    /**
     * The inspection this batch is ready for at its stage, if any: in-process on the line, final once complete, and
     * outgoing once released while it has stock left to ship. The outgoing check is optional.
     */
    public java.util.Optional<String> dueInspection() {
        return switch (status) {
            case IN_PROGRESS -> java.util.Optional.of(IN_PROCESS);
            case COMPLETED -> java.util.Optional.of(FINAL);
            case ON_HOLD -> java.util.Optional.of(producedQuantity == null ? IN_PROCESS : FINAL);
            case RELEASED -> availableQuantity().signum() > 0
                    ? java.util.Optional.of(OUTGOING) : java.util.Optional.empty();
            case REJECTED -> java.util.Optional.empty();
        };
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
                throw new BusinessRuleException(
                        "Final inspection needs a completed batch. Receive the finished goods on the work order first.");
            }
        } else if (OUTGOING.equals(inspectionType)) {
            if (status != Status.RELEASED || availableQuantity().signum() <= 0) {
                throw new BusinessRuleException("Outgoing inspection needs a released batch with stock left to ship.");
            }
        } else {
            throw new BusinessRuleException("A production batch takes in-process, final or outgoing inspection templates.");
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
                // An outgoing check starts on a released batch, so passing it, even after a hold, leaves it released.
                if (FINAL.equals(inspectionType) || OUTGOING.equals(inspectionType)) {
                    status = Status.RELEASED;
                } else {
                    resume();
                }
            }
            case "REJECT" -> status = Status.REJECTED;
            default -> status = Status.ON_HOLD;
        }
    }

    /**
     * A measurement behind it is in doubt. It waits for a new inspection: in-process while it is still being made,
     * final once it is. What already left stays out. A rejected or held batch has nothing more to hold.
     *
     * @return whether the batch was put on hold
     */
    public boolean holdForReinspection() {
        if (status == Status.REJECTED || status == Status.ON_HOLD) {
            return false;
        }
        status = Status.ON_HOLD;
        return true;
    }

    private void resume() {
        if (status == Status.ON_HOLD) {
            status = producedQuantity == null ? Status.IN_PROGRESS : Status.COMPLETED;
        }
    }

    public BigDecimal availableQuantity() {
        return producedQuantity == null
                ? BigDecimal.ZERO
                : producedQuantity.subtract(usedQuantity).subtract(dispatchedQuantity).subtract(transferredQuantity);
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
            throw new BusinessRuleException("Batch %s has only %s available.".formatted(batchNumber,
                    availableQuantity().stripTrailingZeros().toPlainString()));
        }
    }
}
