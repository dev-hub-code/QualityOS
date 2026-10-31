package com.qualityos.traceability.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Returned goods stay out of stock until an inspection accepts them. Only inspections move this past waiting. */
@Getter
@Entity
@Table(name = "dispatch_reversal_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DispatchReversalItem extends TenantOwnedEntity {

    public enum Status { EXPECTED, RETURN_RECEIVED, PENDING_INSPECTION, ACCEPTED, REJECTED, CANCELLED }

    private UUID reversalId;
    private UUID dispatchItemId;
    private UUID productionBatchId;
    private BigDecimal quantity;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> serialNumbers = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private Status status = Status.RETURN_RECEIVED;

    private String disposition;

    public DispatchReversalItem(
            UUID reversalId,
            UUID dispatchItemId,
            UUID productionBatchId,
            BigDecimal quantity,
            List<String> serialNumbers,
            boolean expected) {
        this.reversalId = reversalId;
        this.dispatchItemId = dispatchItemId;
        this.productionBatchId = productionBatchId;
        this.quantity = quantity;
        this.serialNumbers = new ArrayList<>(serialNumbers);
        this.status = expected ? Status.EXPECTED : Status.RETURN_RECEIVED;
    }

    public boolean isDecided() {
        return status == Status.ACCEPTED || status == Status.REJECTED;
    }

    public void inspectionStarted() {
        if (status != Status.RETURN_RECEIVED) {
            throw new BusinessRuleException("These returned goods are not waiting for an inspection.");
        }
        status = Status.PENDING_INSPECTION;
    }

    public boolean isCancelled() {
        return status == Status.CANCELLED;
    }

    public void received() {
        status = Status.RETURN_RECEIVED;
    }

    public void cancel() {
        status = Status.CANCELLED;
    }

    public void inspectionCancelled() {
        status = Status.RETURN_RECEIVED;
    }

    public void decide(boolean accepted, String disposition) {
        status = accepted ? Status.ACCEPTED : Status.REJECTED;
        this.disposition = disposition;
    }

    /** A hold leaves the goods in quarantine, and shows why. */
    public void held(String disposition) {
        this.disposition = disposition;
    }
}
