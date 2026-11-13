package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** One entry on an operation's timeline: a change of status, an assignment or a comment. Never changed. */
@Getter
@Entity
@Immutable
@Table(name = "operation_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OperationEvent extends TenantOwnedEntity {

    public enum Type { READY, STARTED, PAUSED, RESUMED, COMPLETED, BLOCKED, UNBLOCKED, CANCELLED, ASSIGNED, COMMENT }

    /** Why an operation stopped. The same categories later feed downtime reporting. */
    public enum LossCategory {
        MACHINE_BREAKDOWN, MATERIAL_SHORTAGE, POWER_FAILURE, QUALITY_ISSUE, OPERATOR_UNAVAILABLE,
        PLANNED_MAINTENANCE, CHANGEOVER, ORDER_ON_HOLD, OTHER
    }

    private UUID workOrderId;
    private UUID operationId;

    @Enumerated(EnumType.STRING)
    private Type eventType;

    @Enumerated(EnumType.STRING)
    private WorkOrderOperation.Status fromStatus;

    @Enumerated(EnumType.STRING)
    private WorkOrderOperation.Status toStatus;

    @Enumerated(EnumType.STRING)
    private LossCategory lossCategory;

    private UUID operatorId;
    private UUID machineId;
    private String comment;

    public OperationEvent(
            WorkOrderOperation operation, Type type, WorkOrderOperation.Status from, LossCategory loss,
            String comment) {
        this.workOrderId = operation.getWorkOrderId();
        this.operationId = operation.getId();
        this.eventType = type;
        this.fromStatus = from;
        this.toStatus = operation.getStatus();
        this.lossCategory = loss;
        this.operatorId = operation.getOperatorId();
        this.machineId = operation.getMachineId();
        this.comment = comment;
    }
}
