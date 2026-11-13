package com.qualityos.production.repository;

import com.qualityos.production.entity.Machine;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MachineRepository extends JpaRepository<Machine, UUID>, JpaSpecificationExecutor<Machine> {

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    List<Machine> findByWorkCenterIdOrderByCode(UUID workCenterId);

    List<Machine> findByWorkCenterIdIn(Collection<UUID> workCenterIds);
}
