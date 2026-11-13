package com.qualityos.production.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A routing operation as released for one work order, and what happens to it on the floor. It moves only along
 * {@link #TRANSITIONS}; its quantities change only through reports. See docs/MANUFACTURING.md.
 */
@Getter
@Entity
@Table(name = "work_order_operations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkOrderOperation extends TenantOwnedEntity {

    public enum Status {
        PENDING, READY, IN_PROGRESS, PAUSED, COMPLETED, BLOCKED, CANCELLED;

        public boolean isClosed() {
            return this == COMPLETED || this == CANCELLED;
        }
    }

    /** Which status each status may change to. A blocked operation goes back to where it was. */
    public static final Map<Status, Set<Status>> TRANSITIONS = Map.of(
            Status.PENDING, EnumSet.of(Status.READY, Status.BLOCKED, Status.CANCELLED),
            Status.READY, EnumSet.of(Status.IN_PROGRESS, Status.BLOCKED, Status.CANCELLED),
            Status.IN_PROGRESS, EnumSet.of(Status.PAUSED, Status.COMPLETED, Status.BLOCKED),
            Status.PAUSED, EnumSet.of(Status.IN_PROGRESS, Status.COMPLETED, Status.BLOCKED),
            Status.BLOCKED, EnumSet.of(Status.PENDING, Status.READY, Status.PAUSED, Status.CANCELLED),
            Status.COMPLETED, EnumSet.noneOf(Status.class),
            Status.CANCELLED, EnumSet.noneOf(Status.class));

    private UUID workOrderId;
    private int sequence;
    private String name;
    private UUID workCenterId;
    private BigDecimal plannedSetupMinutes;
    private BigDecimal plannedRunMinutes;
    private String instructions;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    private UUID operatorId;
    private UUID machineId;
    private String statusReason;

    @Enumerated(EnumType.STRING)
    private Status blockedFrom;

    private Instant startedAt;
    private Instant completedAt;
    private Instant runningSince;
    private long runningSeconds;
    private BigDecimal actualSetupMinutes;
    private BigDecimal goodQuantity = BigDecimal.ZERO;
    private BigDecimal rejectedQuantity = BigDecimal.ZERO;
    private BigDecimal scrapQuantity = BigDecimal.ZERO;
    private BigDecimal reworkQuantity = BigDecimal.ZERO;

    @JdbcTypeCode(SqlTypes.JSON)
    private String parameters = "[]";

    public WorkOrderOperation(
            UUID workOrderId, int sequence, String name, UUID workCenterId, BigDecimal plannedSetupMinutes,
            BigDecimal plannedRunMinutes, String instructions, String parameters) {
        this.workOrderId = workOrderId;
        this.sequence = sequence;
        this.name = name;
        this.workCenterId = workCenterId;
        this.plannedSetupMinutes = plannedSetupMinutes;
        this.plannedRunMinutes = plannedRunMinutes;
        this.instructions = instructions;
        this.parameters = parameters;
    }

    public void makeReady() {
        move(Status.READY, "made ready");
        statusReason = null;
    }

    /** The first start sets when the operation began; every start or resume starts the running clock. */
    public void start(Instant now) {
        boolean resuming = status == Status.PAUSED;
        move(Status.IN_PROGRESS, resuming ? "resumed" : "started");
        if (startedAt == null) {
            startedAt = now;
        }
        runningSince = now;
        statusReason = null;
    }

    public void pause(Instant now, String reason) {
        requireStatus(Status.IN_PROGRESS, "paused");
        stopClock(now);
        status = Status.PAUSED;
        statusReason = reason;
    }

    /** @param reason why less was processed than came in, or null when it was not short */
    public void complete(Instant now, BigDecimal setupMinutes, String reason) {
        move(Status.COMPLETED, "completed");
        stopClock(now);
        completedAt = now;
        actualSetupMinutes = setupMinutes;
        statusReason = reason;
    }

    /** A running operation is stopped first, so that it comes back paused. */
    public void block(Instant now, String reason) {
        Status from = status == Status.IN_PROGRESS ? Status.PAUSED : status;
        move(Status.BLOCKED, "blocked");
        stopClock(now);
        blockedFrom = from;
        statusReason = reason;
    }

    public void unblock() {
        requireStatus(Status.BLOCKED, "unblocked");
        status = blockedFrom;
        blockedFrom = null;
        statusReason = null;
    }

    /** Skipping an operation is only possible before anything was reported on it. */
    public void cancel(String reason) {
        if (status.isClosed()) {
            return;
        }
        if (processed().signum() > 0) {
            throw new BusinessRuleException("Quantities were reported on operation %d, so it cannot be skipped."
                    .formatted(sequence));
        }
        move(Status.CANCELLED, "skipped");
        blockedFrom = null;
        statusReason = reason;
    }

    public void assign(UUID operator, UUID machine) {
        if (status.isClosed()) {
            throw new BusinessRuleException("Operation %d is %s.".formatted(sequence, label(status)));
        }
        if (status == Status.IN_PROGRESS && !java.util.Objects.equals(machine, machineId)) {
            throw new BusinessRuleException("Pause operation %d before changing its machine.".formatted(sequence));
        }
        operatorId = operator;
        machineId = machine;
    }

    /** Adds a report's quantities, or takes a reversed one off. */
    public void record(BigDecimal good, BigDecimal rejected, BigDecimal scrap, BigDecimal rework) {
        goodQuantity = goodQuantity.add(good);
        rejectedQuantity = rejectedQuantity.add(rejected);
        scrapQuantity = scrapQuantity.add(scrap);
        reworkQuantity = reworkQuantity.add(rework);
    }

    /** Everything that went through: good, rejected and scrapped. */
    public BigDecimal processed() {
        return goodQuantity.add(rejectedQuantity).add(scrapQuantity);
    }

    /** Time spent running, including the current run. */
    public long runningSecondsAt(Instant now) {
        return runningSeconds + (runningSince == null ? 0 : Duration.between(runningSince, now).toSeconds());
    }

    public void requireStatus(Status required, String action) {
        if (status != required) {
            throw new BusinessRuleException("Operation %d is %s, so it cannot be %s.".formatted(
                    sequence, label(status), action));
        }
    }

    public void requireOneOf(Set<Status> allowed, String action) {
        if (!allowed.contains(status)) {
            throw new BusinessRuleException("Operation %d is %s, so it cannot be %s.".formatted(
                    sequence, label(status), action));
        }
    }

    private void move(Status target, String action) {
        if (!TRANSITIONS.get(status).contains(target)) {
            throw new BusinessRuleException("Operation %d is %s, so it cannot be %s.".formatted(
                    sequence, label(status), action));
        }
        status = target;
    }

    private void stopClock(Instant now) {
        if (runningSince != null) {
            runningSeconds += Math.max(0, Duration.between(runningSince, now).toSeconds());
            runningSince = null;
        }
    }

    public static String label(Status status) {
        return status.name().toLowerCase().replace('_', ' ');
    }
}
