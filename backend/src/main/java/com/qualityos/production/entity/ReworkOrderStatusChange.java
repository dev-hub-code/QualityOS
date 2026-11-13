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

/** One status a rework order took. Never changed. */
@Getter
@Entity
@Immutable
@Table(name = "rework_order_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReworkOrderStatusChange extends TenantOwnedEntity {

    private UUID reworkOrderId;

    @Enumerated(EnumType.STRING)
    private ReworkOrder.Status fromStatus;

    @Enumerated(EnumType.STRING)
    private ReworkOrder.Status toStatus;

    private String reason;

    public ReworkOrderStatusChange(UUID reworkOrderId, ReworkOrder.Status from, ReworkOrder.Status to, String reason) {
        this.reworkOrderId = reworkOrderId;
        this.fromStatus = from;
        this.toStatus = to;
        this.reason = reason;
    }
}
