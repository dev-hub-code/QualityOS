package com.qualityos.materialreceipt.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import com.qualityos.common.util.DayLabels;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "material_receipt_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MaterialReceiptItem extends TenantOwnedEntity {

    public enum InspectionStatus { NOT_REQUIRED, PENDING, IN_PROGRESS, COMPLETED, CANCELLED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id")
    private MaterialReceipt receipt;

    private UUID purchaseOrderItemId;
    private UUID materialId;
    private UUID uomId;
    private String supplierBatch;
    private String internalBatch;
    private BigDecimal quantity;
    private BigDecimal consumedQuantity = BigDecimal.ZERO;

    /** Set aside for work orders. Ordinary consumption cannot take it. */
    private BigDecimal reservedQuantity = BigDecimal.ZERO;
    private boolean inspectionRequired;

    @Enumerated(EnumType.STRING)
    private InspectionStatus inspectionStatus;

    private String disposition;

    /** The last day the lot may be used; null when it does not expire. */
    private java.time.LocalDate expiryDate;

    /** Sent to another plant, in transit or received there as a lot of its own. */
    private BigDecimal transferredQuantity = BigDecimal.ZERO;

    /** Where a lot received from another plant is. Null for a lot still at the plant of its goods receipt. */
    private UUID facilityId;

    /** The lot this one was sent from, for a lot received from another plant. */
    private UUID transferredFromId;

    /** As counted, such as 4 DRUM, when that is not the material's own unit; quantity is in the material's. */
    private BigDecimal enteredQuantity;
    private UUID enteredUomId;

    public MaterialReceiptItem(
            MaterialReceipt receipt,
            UUID purchaseOrderItemId,
            UUID materialId,
            UUID uomId,
            String supplierBatch,
            String internalBatch,
            BigDecimal quantity,
            boolean inspectionRequired) {
        this.receipt = receipt;
        this.purchaseOrderItemId = purchaseOrderItemId;
        this.materialId = materialId;
        this.uomId = uomId;
        this.supplierBatch = supplierBatch;
        this.internalBatch = internalBatch;
        this.quantity = quantity;
        this.inspectionRequired = inspectionRequired;
        this.inspectionStatus = inspectionRequired ? InspectionStatus.PENDING : InspectionStatus.NOT_REQUIRED;
    }

    /** Keeps what was counted when it was in another unit than the material's own. */
    public void countedAs(BigDecimal quantity, UUID uomId) {
        this.enteredQuantity = quantity;
        this.enteredUomId = uomId;
    }

    /**
     * The part of {@code source} received at another plant. It keeps the receipt, supplier batch, inspection result and
     * expiry of the lot it came from, so it is used as that lot would be.
     */
    public static MaterialReceiptItem receivedFrom(
            MaterialReceiptItem source, String internalBatch, BigDecimal quantity, UUID facilityId) {
        MaterialReceiptItem lot = new MaterialReceiptItem(source.receipt, source.purchaseOrderItemId, source.materialId,
                source.uomId, source.supplierBatch, internalBatch, quantity, source.inspectionRequired);
        lot.inspectionStatus = source.inspectionStatus;
        lot.disposition = source.disposition;
        lot.expiryDate = source.expiryDate;
        lot.facilityId = facilityId;
        lot.transferredFromId = source.getId();
        return lot;
    }

    /** The plant the lot is at: its goods receipt's, unless it was received from another plant. */
    public UUID currentFacilityId() {
        return facilityId != null ? facilityId : receipt.getFacilityId();
    }

    /** Sends part of the lot to another plant. Only released stock not set aside can go. */
    public void transferOut(BigDecimal amount) {
        requireReleased();
        if (amount.compareTo(freeQuantity()) > 0) {
            throw new BusinessRuleException("Lot " + internalBatch + " has only " + plain(freeQuantity()) + " free.");
        }
        transferredQuantity = transferredQuantity.add(amount);
    }

    /** A transfer cancelled while in transit: the stock is back. */
    public void returnTransferred(BigDecimal amount) {
        transferredQuantity = transferredQuantity.subtract(amount);
    }

    public void expiresOn(java.time.LocalDate date) {
        this.expiryDate = date;
    }

    /** Whether the lot is past its expiry date on {@code today}. It may still be used on the date itself. */
    public boolean isExpiredOn(java.time.LocalDate today) {
        return expiryDate != null && expiryDate.isBefore(today);
    }

    /** Expired stock is not used in production, whoever asks. */
    public void requireNotExpired(java.time.LocalDate today) {
        if (isExpiredOn(today)) {
            throw new BusinessRuleException(
                    "Batch " + internalBatch + " expired on " + DayLabels.label(expiryDate) + ".");
        }
    }

    void cancelInspection() {
        inspectionStatus = InspectionStatus.CANCELLED;
    }

    public boolean inspectionStarted() {
        return inspectionStatus == InspectionStatus.IN_PROGRESS || inspectionStatus == InspectionStatus.COMPLETED;
    }

    /** Only material that has passed its gate may enter production. Anything else stays out, whoever asks. */
    public boolean isReleased() {
        return inspectionStatus == InspectionStatus.NOT_REQUIRED
                || (inspectionStatus == InspectionStatus.COMPLETED
                        && ("ACCEPT".equals(disposition) || "CONDITIONAL_ACCEPTANCE".equals(disposition)));
    }

    public BigDecimal remainingQuantity() {
        return quantity.subtract(consumedQuantity).subtract(transferredQuantity);
    }

    /** What is left and not set aside for a work order. */
    public BigDecimal freeQuantity() {
        return remainingQuantity().subtract(reservedQuantity);
    }

    public void consume(BigDecimal amount) {
        requireReleased();
        if (amount.compareTo(freeQuantity()) > 0) {
            throw new BusinessRuleException("Batch " + internalBatch + " has only " + plain(freeQuantity())
                    + " free" + (reservedQuantity.signum() > 0 ? ", the rest is reserved." : "."));
        }
        consumedQuantity = consumedQuantity.add(amount);
    }

    /** Sets stock aside. Only released stock that is not already set aside can be. */
    public void reserve(BigDecimal amount) {
        requireReleased();
        if (amount.compareTo(freeQuantity()) > 0) {
            throw new BusinessRuleException("Lot " + internalBatch + " has only " + plain(freeQuantity()) + " free.");
        }
        reservedQuantity = reservedQuantity.add(amount);
    }

    public void unreserve(BigDecimal amount) {
        if (amount.compareTo(reservedQuantity) > 0) {
            throw new BusinessRuleException("Batch " + internalBatch + " has only " + plain(reservedQuantity) + " reserved.");
        }
        reservedQuantity = reservedQuantity.subtract(amount);
    }

    /** Uses stock that was set aside for this use. */
    public void consumeReserved(BigDecimal amount) {
        requireReleased();
        unreserve(amount);
        consumedQuantity = consumedQuantity.add(amount);
    }

    private void requireReleased() {
        if (!isReleased()) {
            throw new BusinessRuleException("Batch " + internalBatch + " has not been released for use.");
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** Corrections only: returns stock taken by a consumption that was reversed. */
    public void restore(BigDecimal amount) {
        consumedQuantity = consumedQuantity.subtract(amount);
    }

    /** Corrections only. The original values stay on the correction that changed them. */
    public void correct(UUID materialId, String supplierBatch, BigDecimal quantity) {
        if (materialId != null) {
            this.materialId = materialId;
        }
        if (supplierBatch != null) {
            this.supplierBatch = supplierBatch;
        }
        if (quantity != null) {
            if (quantity.compareTo(consumedQuantity) < 0) {
                throw new BusinessRuleException("Batch " + internalBatch + " has already used more than " + quantity + ".");
            }
            if (quantity.compareTo(consumedQuantity.add(reservedQuantity)) < 0) {
                throw new BusinessRuleException("Batch " + internalBatch + " has stock reserved for work orders beyond "
                        + quantity + ". Release those reservations first.");
            }
            this.quantity = quantity;
        }
    }

    /**
     * A measurement behind its release is in doubt, so the lot waits for a new inspection. Stock already used stays
     * used. A lot that was never released has nothing to hold.
     *
     * @return whether the lot was released before
     */
    public boolean holdForReinspection() {
        if (inspectionStatus != InspectionStatus.COMPLETED || !isReleased()) {
            return false;
        }
        inspectionStatus = InspectionStatus.PENDING;
        disposition = null;
        return true;
    }

    public void updateInspection(InspectionStatus status, String disposition) {
        this.inspectionStatus = status;
        this.disposition = disposition;
    }
}
