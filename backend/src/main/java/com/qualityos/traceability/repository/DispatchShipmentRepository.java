package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.DispatchShipment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DispatchShipmentRepository extends JpaRepository<DispatchShipment, UUID> {

    boolean existsByDispatchId(UUID dispatchId);

    Optional<DispatchShipment> findByDispatchId(UUID dispatchId);

    List<DispatchShipment> findByDispatchIdIn(Collection<UUID> dispatchIds);
}
