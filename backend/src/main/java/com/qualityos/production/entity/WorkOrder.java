package com.qualityos.production.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An order to make a quantity of a product. It moves only along {@link #TRANSITIONS}; release freezes what it is made
 * from. See docs/MANUFACTURING.md.
 */
@Getter
@Entity
@Table(name = "work_orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkOrder extends TenantOwnedEntity {

    public enum Status {
        DRAFT, SUBMITTED, APPROVED, RELEASED, IN_PROGRESS, ON_HOLD, PARTIALLY_COMPLETED, COMPLETED, CANCELLED;

        /** Completed, partially completed and cancelled orders are closed for good. */
        public boolean isOpen() {
            return this != COMPLETED && this != PARTIALLY_COMPLETED && this != CANCELLED;
        }
    }

    public enum Priority { LOW, NORMAL, HIGH, URGENT }

    /**
     * Which status each status may change to. An order in progress closes as COMPLETED or PARTIALLY_COMPLETED once its
     * finished goods are received; see CompletionService.
     */
    public static final Map<Status, Set<Status>> TRANSITIONS = Map.of(
            Status.DRAFT, EnumSet.of(Status.SUBMITTED, Status.CANCELLED),
            Status.SUBMITTED, EnumSet.of(Status.DRAFT, Status.APPROVED, Status.CANCELLED),
            Status.APPROVED, EnumSet.of(Status.RELEASED, Status.ON_HOLD, Status.CANCELLED),
            Status.RELEASED, EnumSet.of(Status.IN_PROGRESS, Status.ON_HOLD, Status.CANCELLED),
            Status.IN_PROGRESS, EnumSet.of(Status.ON_HOLD, Status.COMPLETED, Status.PARTIALLY_COMPLETED),
            Status.ON_HOLD, EnumSet.of(Status.APPROVED, Status.RELEASED, Status.IN_PROGRESS, Status.CANCELLED),
            Status.PARTIALLY_COMPLETED, EnumSet.noneOf(Status.class),
            Status.COMPLETED, EnumSet.noneOf(Status.class),
            Status.CANCELLED, EnumSet.noneOf(Status.class));

    private String workOrderNumber;

    @Setter
    private UUID productId;

    @Setter
    private UUID facilityId;

    @Setter
    private UUID productionLineId;

    @Setter
    private BigDecimal quantity;

    @Setter
    private UUID uomId;

    @Setter
    private LocalDate plannedStart;

    @Setter
    private LocalDate plannedEnd;

    @Setter
    @Enumerated(EnumType.STRING)
    private Priority priority = Priority.NORMAL;

    private UUID planLineId;
    private UUID bomId;
    private UUID routingId;

    @Enumerated(EnumType.STRING)
    private Status status = Status.DRAFT;

    @Enumerated(EnumType.STRING)
    private Status heldFrom;

    private String statusReason;

    @Setter
    private String notes;

    private UUID submittedBy;
    private Instant submittedAt;
    private UUID approvedBy;
    private Instant approvedAt;
    private UUID releasedBy;
    private Instant releasedAt;
    private Instant startedAt;
    private UUID productionBatchId;
    private UUID completedBy;
    private Instant completedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    private String definitionSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    private String routingSnapshot;

    public WorkOrder(String workOrderNumber, UUID planLineId) {
        this.workOrderNumber = workOrderNumber;
        this.planLineId = planLineId;
    }

    public void requireDraft() {
        if (status != Status.DRAFT) {
            throw new BusinessRuleException("Work order %s is %s, so it cannot be changed.".formatted(
                    workOrderNumber, label(status)));
        }
    }

    public void submit(UUID actor, Instant now) {
        move(Status.SUBMITTED, Status.DRAFT, "submitted");
        submittedBy = actor;
        submittedAt = now;
        statusReason = null;
    }

    public void sendBack(String note) {
        move(Status.DRAFT, Status.SUBMITTED, "sent back");
        statusReason = note;
    }

    public void approve(UUID actor, Instant now) {
        move(Status.APPROVED, Status.SUBMITTED, "approved");
        approvedBy = actor;
        approvedAt = now;
    }

    public void release(UUID actor, Instant now, UUID bom, String definition, UUID routing, String routingFrozen) {
        move(Status.RELEASED, Status.APPROVED, "released");
        releasedBy = actor;
        releasedAt = now;
        bomId = bom;
        definitionSnapshot = definition;
        routingId = routing;
        routingSnapshot = routingFrozen;
    }

    public void start(UUID batchId, Instant now) {
        move(Status.IN_PROGRESS, Status.RELEASED, "started");
        productionBatchId = batchId;
        startedAt = now;
    }

    public void hold(String reason) {
        transition(Status.ON_HOLD);
        heldFrom = status;
        status = Status.ON_HOLD;
        statusReason = reason;
    }

    public void resume() {
        if (status != Status.ON_HOLD) {
            throw new BusinessRuleException("Work order %s is not on hold.".formatted(workOrderNumber));
        }
        status = heldFrom;
        heldFrom = null;
        statusReason = null;
    }

    /** Once production has started, the order is finished through completion, not cancelled. */
    public void cancel(String reason) {
        if (status == Status.ON_HOLD && heldFrom == Status.IN_PROGRESS) {
            throw new BusinessRuleException("Production has started on %s, so it cannot be cancelled."
                    .formatted(workOrderNumber));
        }
        transition(Status.CANCELLED);
        status = Status.CANCELLED;
        heldFrom = null;
        statusReason = reason;
    }

    /**
     * Closes the order once its finished goods are received: completed, or partially completed when it closes short.
     * Nothing about it changes afterwards.
     */
    public void complete(boolean full, UUID actor, Instant now, String reason) {
        Status target = full ? Status.COMPLETED : Status.PARTIALLY_COMPLETED;
        transition(target);
        status = target;
        completedBy = actor;
        completedAt = now;
        statusReason = reason;
    }

    /** Whether cancelling it now needs an approver: anything approved has been committed to. */
    public boolean committed() {
        return status != Status.DRAFT && status != Status.SUBMITTED;
    }

    /** Checks before work that has to be done ahead of the change itself, such as opening a batch. */
    public void requireStatus(Status required, String action) {
        if (status != required) {
            throw new BusinessRuleException("Work order %s is %s, so it cannot be %s.".formatted(
                    workOrderNumber, label(status), action));
        }
    }

    private void move(Status target, Status required, String action) {
        requireStatus(required, action);
        status = target;
    }

    private void transition(Status target) {
        if (!TRANSITIONS.get(status).contains(target)) {
            throw new BusinessRuleException("Work order %s is %s, so it cannot become %s.".formatted(
                    workOrderNumber, label(status), label(target)));
        }
    }

    private static String label(Status status) {
        return status.name().toLowerCase().replace('_', ' ');
    }
}
