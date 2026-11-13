package com.qualityos.production.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One step of a rework. Once done it stays done. */
@Getter
@Entity
@Table(name = "rework_order_steps")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReworkOrderStep extends TenantOwnedEntity {

    private UUID reworkOrderId;
    private int sequence;
    private String description;
    private UUID workCenterId;
    private UUID completedBy;
    private Instant completedAt;
    private String note;

    public ReworkOrderStep(UUID reworkOrderId, int sequence, String description, UUID workCenterId) {
        this.reworkOrderId = reworkOrderId;
        this.sequence = sequence;
        this.description = description;
        this.workCenterId = workCenterId;
    }

    public boolean isDone() {
        return completedAt != null;
    }

    public void complete(UUID actor, Instant now, String note) {
        if (isDone()) {
            throw new BusinessRuleException("Step %d is already done.".formatted(sequence));
        }
        completedBy = actor;
        completedAt = now;
        this.note = note;
    }
}
