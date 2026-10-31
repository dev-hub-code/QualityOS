package com.qualityos.subscription.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** What a tenant may do follows from the dates here, so access stays right even if no job has run. */
@Getter
@Entity
@Table(name = "subscriptions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Subscription extends TenantOwnedEntity {

    public enum Status { TRIALING, ACTIVE }

    public enum Cycle { MONTHLY, ANNUAL }

    private UUID planId;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Enumerated(EnumType.STRING)
    private Cycle billingCycle;

    private Instant currentPeriodEndsAt;
    private Instant cancelledAt;

    @Setter
    private Integer lastNoticeDays;

    public Subscription(UUID planId, Instant trialEndsAt) {
        this.planId = planId;
        this.status = Status.TRIALING;
        this.currentPeriodEndsAt = trialEndsAt;
    }

    public void cancel(Instant at) {
        cancelledAt = at;
    }

    public void resume() {
        cancelledAt = null;
    }

    public void extend(Duration by) {
        currentPeriodEndsAt = currentPeriodEndsAt.plus(by);
        lastNoticeDays = null;
    }

    /** @return when the paid period starts. Paying for the same plan early adds to the time left. */
    public Instant activate(UUID plan, Cycle cycle, Instant now) {
        Instant start = plan.equals(planId) && currentPeriodEndsAt.isAfter(now) ? currentPeriodEndsAt : now;
        var from = start.atOffset(ZoneOffset.UTC);
        planId = plan;
        status = Status.ACTIVE;
        billingCycle = cycle;
        currentPeriodEndsAt = (cycle == Cycle.ANNUAL ? from.plusYears(1) : from.plusMonths(1)).toInstant();
        cancelledAt = null;
        lastNoticeDays = null;
        return start;
    }
}
