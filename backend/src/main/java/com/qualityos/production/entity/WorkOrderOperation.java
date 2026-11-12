package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A routing operation as released for one work order. Shop-floor execution (phase 4) moves it through its statuses. */
@Getter
@Entity
@Table(name = "work_order_operations")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkOrderOperation extends TenantOwnedEntity {

    public enum Status { PENDING, READY, IN_PROGRESS, PAUSED, COMPLETED, BLOCKED, CANCELLED }

    private UUID workOrderId;
    private int sequence;
    private String name;
    private UUID workCenterId;
    private BigDecimal plannedSetupMinutes;
    private BigDecimal plannedRunMinutes;
    private String instructions;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    public WorkOrderOperation(
            UUID workOrderId, int sequence, String name, UUID workCenterId, BigDecimal plannedSetupMinutes,
            BigDecimal plannedRunMinutes, String instructions) {
        this.workOrderId = workOrderId;
        this.sequence = sequence;
        this.name = name;
        this.workCenterId = workCenterId;
        this.plannedSetupMinutes = plannedSetupMinutes;
        this.plannedRunMinutes = plannedRunMinutes;
        this.instructions = instructions;
    }

    public void cancel() {
        status = Status.CANCELLED;
    }
}
