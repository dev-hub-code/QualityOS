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

/** One status a machine took. Never changed: the database refuses it. */
@Getter
@Entity
@Immutable
@Table(name = "machine_status_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MachineStatusEvent extends TenantOwnedEntity {

    private UUID machineId;

    @Enumerated(EnumType.STRING)
    private Machine.Status fromStatus;

    @Enumerated(EnumType.STRING)
    private Machine.Status toStatus;

    private String reason;

    public MachineStatusEvent(UUID machineId, Machine.Status fromStatus, Machine.Status toStatus, String reason) {
        this.machineId = machineId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
    }
}
