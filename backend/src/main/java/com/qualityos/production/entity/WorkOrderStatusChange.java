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

/** One status a work order took. Never changed: the database refuses it. */
@Getter
@Entity
@Immutable
@Table(name = "work_order_status_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkOrderStatusChange extends TenantOwnedEntity {

    private UUID workOrderId;

    @Enumerated(EnumType.STRING)
    private WorkOrder.Status fromStatus;

    @Enumerated(EnumType.STRING)
    private WorkOrder.Status toStatus;

    private String reason;

    public WorkOrderStatusChange(UUID workOrderId, WorkOrder.Status fromStatus, WorkOrder.Status toStatus, String reason) {
        this.workOrderId = workOrderId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
    }
}
