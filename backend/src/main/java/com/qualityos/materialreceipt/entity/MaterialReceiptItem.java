package com.qualityos.materialreceipt.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
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
    private boolean inspectionRequired;

    @Enumerated(EnumType.STRING)
    private InspectionStatus inspectionStatus;

    private String disposition;

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
        return quantity.subtract(consumedQuantity);
    }

    public void consume(BigDecimal amount) {
        if (!isReleased()) {
            throw new BusinessRuleException("Batch " + internalBatch + " has not been released for use.");
        }
        if (amount.compareTo(remainingQuantity()) > 0) {
            throw new BusinessRuleException("Batch " + internalBatch + " has only " + remainingQuantity() + " left.");
        }
        consumedQuantity = consumedQuantity.add(amount);
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
            this.quantity = quantity;
        }
    }

    public void updateInspection(InspectionStatus status, String disposition) {
        this.inspectionStatus = status;
        this.disposition = disposition;
    }
}
