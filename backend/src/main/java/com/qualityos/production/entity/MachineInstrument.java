package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** An instrument kept at a machine, offered first when measuring on it. */
@Getter
@Entity
@Table(name = "machine_instruments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MachineInstrument extends TenantOwnedEntity {

    private UUID machineId;
    private UUID instrumentId;

    public MachineInstrument(UUID machineId, UUID instrumentId) {
        this.machineId = machineId;
        this.instrumentId = instrumentId;
    }
}
