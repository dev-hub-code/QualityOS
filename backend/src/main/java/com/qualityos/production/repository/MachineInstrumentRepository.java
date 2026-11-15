package com.qualityos.production.repository;

import com.qualityos.production.entity.MachineInstrument;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MachineInstrumentRepository extends JpaRepository<MachineInstrument, UUID> {

    List<MachineInstrument> findByMachineIdIn(Collection<UUID> machineIds);

    Optional<MachineInstrument> findByMachineIdAndInstrumentId(UUID machineId, UUID instrumentId);
}
