package com.qualityos.materialreceipt.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A record of a delivery. It is not edited afterwards: a mistake is voided and received again. */
@Getter
@Entity
@Table(name = "material_receipts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MaterialReceipt extends TenantOwnedEntity {

    public enum Status { RECEIVED, VOIDED }

    private String grnNumber;
    private UUID purchaseOrderId;
    private UUID supplierId;
    private UUID facilityId;
    private LocalDate receivedOn;
    private String deliveryNote;
    private String notes;

    @Enumerated(EnumType.STRING)
    private Status status = Status.RECEIVED;

    private String voidReason;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MaterialReceiptItem> items = new ArrayList<>();

    public MaterialReceipt(
            String grnNumber,
            UUID purchaseOrderId,
            UUID supplierId,
            UUID facilityId,
            LocalDate receivedOn,
            String deliveryNote,
            String notes) {
        this.grnNumber = grnNumber;
        this.purchaseOrderId = purchaseOrderId;
        this.supplierId = supplierId;
        this.facilityId = facilityId;
        this.receivedOn = receivedOn;
        this.deliveryNote = deliveryNote;
        this.notes = notes;
    }

    public void voidReceipt(String reason) {
        if (status == Status.VOIDED) {
            throw new BusinessRuleException("This receipt is already void.");
        }
        if (items.stream().anyMatch(MaterialReceiptItem::inspectionStarted)) {
            throw new BusinessRuleException("This receipt has an inspection, so it cannot be voided.");
        }
        status = Status.VOIDED;
        voidReason = reason;
        items.forEach(MaterialReceiptItem::cancelInspection);
    }
}
