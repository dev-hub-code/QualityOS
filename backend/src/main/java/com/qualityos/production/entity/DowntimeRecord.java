package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import com.qualityos.production.entity.OperationEvent.LossCategory;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A stop of production at a work centre, on a machine or an operation. Planned maintenance is planned downtime; every
 * other category is a loss. Kept for good: see the trigger in V28.
 */
@Getter
@Setter
@Entity
@Table(name = "downtime_records")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DowntimeRecord extends TenantOwnedEntity {

    public enum Source { SHOP_FLOOR, MACHINE, MANUAL }

    private UUID facilityId;
    private UUID workCenterId;
    private UUID machineId;
    private UUID workOrderId;
    private UUID operationId;

    @Enumerated(EnumType.STRING)
    private Source source;

    @Enumerated(EnumType.STRING)
    private LossCategory category;

    private boolean planned;
    private Instant startedAt;
    private Instant endedAt;
    private UUID endedBy;
    private String reason;
    private String responsibleDepartment;
    private String comments;

    @SuppressWarnings("java:S107")
    public DowntimeRecord(Source source, UUID facilityId, UUID workCenterId, UUID machineId, UUID workOrderId,
            UUID operationId, LossCategory category, Instant startedAt, String reason) {
        this.source = source;
        this.facilityId = facilityId;
        this.workCenterId = workCenterId;
        this.machineId = machineId;
        this.workOrderId = workOrderId;
        this.operationId = operationId;
        this.startedAt = startedAt;
        this.reason = reason;
        categorise(category);
    }

    /** Planned maintenance is planned downtime; the category decides it. */
    public void categorise(LossCategory value) {
        this.category = value;
        this.planned = value == LossCategory.PLANNED_MAINTENANCE;
    }

    public boolean isOpen() {
        return endedAt == null;
    }

    public void end(Instant at, UUID by) {
        this.endedAt = at.isBefore(startedAt) ? startedAt : at;
        this.endedBy = by;
    }

    /** Minutes of the stop inside [from, to), counting an open stop up to now. */
    public double minutesWithin(Instant from, Instant to, Instant now) {
        Instant end = endedAt == null ? now : endedAt;
        Instant a = startedAt.isAfter(from) ? startedAt : from;
        Instant b = end.isBefore(to) ? end : to;
        return b.isAfter(a) ? Duration.between(a, b).toSeconds() / 60.0 : 0;
    }
}
