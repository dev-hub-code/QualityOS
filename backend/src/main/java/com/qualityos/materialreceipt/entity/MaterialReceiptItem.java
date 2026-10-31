package com.qualityos.materialreceipt.entity;

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

    public void updateInspection(InspectionStatus status, String disposition) {
        this.inspectionStatus = status;
        this.disposition = disposition;
    }
}
