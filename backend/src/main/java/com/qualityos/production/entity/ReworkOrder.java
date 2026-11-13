package com.qualityos.production.entity;

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

/**
 * Rework of rejected production, done on the batch it came from so that its genealogy is unchanged. It needs an
 * authorised disposition before anyone works on it, and a reinspection before it is closed. See docs/MANUFACTURING.md.
 */
@Getter
@Entity
@Table(name = "rework_orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReworkOrder extends TenantOwnedEntity {

    public enum Status {
        OPEN, APPROVED, IN_PROGRESS, AWAITING_INSPECTION, CLOSED, CANCELLED;

        public boolean isOpen() {
            return this != CLOSED && this != CANCELLED;
        }
    }

    private String reworkNumber;
    private UUID workOrderId;
    private UUID productionBatchId;
    private UUID operationId;
    private UUID ncrId;
    private UUID sourceInspectionId;
    private BigDecimal quantity;
    private UUID uomId;
    private String reason;
    private String instructions;

    @Enumerated(EnumType.STRING)
    private Status status = Status.OPEN;

    private String statusReason;
    private UUID approvedBy;
    private Instant approvedAt;
    private Instant startedAt;
    private Instant completedAt;
    private BigDecimal reworkedQuantity;
    private BigDecimal scrappedQuantity;
    private UUID reinspectionId;
    private String finalDisposition;
    private Instant closedAt;

    @SuppressWarnings("java:S107")
    public ReworkOrder(
            String reworkNumber, UUID workOrderId, UUID productionBatchId, UUID operationId, UUID ncrId,
            UUID sourceInspectionId, BigDecimal quantity, UUID uomId, String reason, String instructions) {
        this.reworkNumber = reworkNumber;
        this.workOrderId = workOrderId;
        this.productionBatchId = productionBatchId;
        this.operationId = operationId;
        this.ncrId = ncrId;
        this.sourceInspectionId = sourceInspectionId;
        this.quantity = quantity;
        this.uomId = uomId;
        this.reason = reason;
        this.instructions = instructions;
    }

    public void approve(UUID actor, Instant now) {
        move(Status.APPROVED, Status.OPEN, "approved");
        approvedBy = actor;
        approvedAt = now;
    }

    public void start(Instant now) {
        move(Status.IN_PROGRESS, Status.APPROVED, "started");
        startedAt = now;
    }

    public void complete(BigDecimal reworked, BigDecimal scrapped, Instant now) {
        requireStatus(Status.IN_PROGRESS, "completed");
        if (reworked.add(scrapped).compareTo(quantity) != 0) {
            throw new BusinessRuleException("Reworked %s and scrapped %s make %s, not the %s on the order.".formatted(
                    plain(reworked), plain(scrapped), plain(reworked.add(scrapped)), plain(quantity)));
        }
        reworkedQuantity = reworked;
        scrappedQuantity = scrapped;
        completedAt = now;
        status = Status.AWAITING_INSPECTION;
    }

    public void reinspectedBy(UUID inspectionId) {
        requireStatus(Status.AWAITING_INSPECTION, "reinspected");
        reinspectionId = inspectionId;
    }

    /** @param disposition what the reinspection decided for the reworked goods */
    public void close(String disposition, Instant now) {
        requireStatus(Status.AWAITING_INSPECTION, "closed");
        finalDisposition = disposition;
        closedAt = now;
        status = Status.CLOSED;
    }

    public void cancel(String why) {
        if (status != Status.OPEN && status != Status.APPROVED) {
            throw new BusinessRuleException("Rework order %s is %s, so it cannot be cancelled.".formatted(
                    reworkNumber, label(status)));
        }
        status = Status.CANCELLED;
        statusReason = why;
    }

    public void requireStatus(Status required, String action) {
        if (status != required) {
            throw new BusinessRuleException("Rework order %s is %s, so it cannot be %s.".formatted(
                    reworkNumber, label(status), action));
        }
    }

    private void move(Status target, Status required, String action) {
        requireStatus(required, action);
        status = target;
    }

    public static String label(Status status) {
        return status.name().toLowerCase().replace('_', ' ');
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
