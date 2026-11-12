package com.qualityos.production.repository;

import com.qualityos.production.entity.MachineStatusEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MachineStatusEventRepository extends JpaRepository<MachineStatusEvent, UUID> {

    List<MachineStatusEvent> findTop100ByMachineIdOrderByCreatedAtDesc(UUID machineId);
}
