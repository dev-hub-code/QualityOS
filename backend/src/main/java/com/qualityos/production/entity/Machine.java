package com.qualityos.production.entity;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "machines")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Machine extends TenantOwnedEntity {

    public enum Status {
        AVAILABLE, RUNNING, IDLE, MAINTENANCE, BREAKDOWN, INACTIVE;

        /** A machine stopped for a reason has to say why. */
        public boolean needsReason() {
            return this == MAINTENANCE || this == BREAKDOWN || this == INACTIVE;
        }
    }

    /** Which status each status may change to. See docs/MANUFACTURING.md. */
    public static final Map<Status, Set<Status>> TRANSITIONS = Map.of(
            Status.AVAILABLE, EnumSet.of(Status.RUNNING, Status.IDLE, Status.MAINTENANCE, Status.BREAKDOWN,
                    Status.INACTIVE),
            Status.RUNNING, EnumSet.of(Status.AVAILABLE, Status.IDLE, Status.MAINTENANCE, Status.BREAKDOWN),
            Status.IDLE, EnumSet.of(Status.AVAILABLE, Status.RUNNING, Status.MAINTENANCE, Status.BREAKDOWN,
                    Status.INACTIVE),
            Status.MAINTENANCE, EnumSet.of(Status.AVAILABLE, Status.BREAKDOWN, Status.INACTIVE),
            Status.BREAKDOWN, EnumSet.of(Status.MAINTENANCE, Status.AVAILABLE, Status.INACTIVE),
            Status.INACTIVE, EnumSet.of(Status.AVAILABLE));

    private UUID workCenterId;
    private String code;
    private String name;
    private String manufacturer;
    private String model;
    private String serialNumber;

    @Enumerated(EnumType.STRING)
    @Setter(AccessLevel.NONE)
    private Status status = Status.AVAILABLE;

    @Setter(AccessLevel.NONE)
    private String statusReason;

    @Setter(AccessLevel.NONE)
    private Instant statusChangedAt = Instant.now();

    public Machine(UUID workCenterId, String code) {
        this.workCenterId = workCenterId;
        this.code = code;
    }

    public void changeStatus(Status target, String reason, Instant now) {
        if (!TRANSITIONS.get(status).contains(target)) {
            throw new BusinessRuleException("A machine that is %s cannot become %s.".formatted(
                    status.name().toLowerCase(), target.name().toLowerCase()));
        }
        if (target.needsReason() && (reason == null || reason.isBlank())) {
            throw new BusinessRuleException("Say why the machine is %s.".formatted(target.name().toLowerCase()));
        }
        status = target;
        statusReason = reason == null || reason.isBlank() ? null : reason.trim();
        statusChangedAt = now;
    }
}
