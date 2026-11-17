package com.qualityos.production.repository;

import com.qualityos.production.entity.MaterialMovement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialMovementRepository extends JpaRepository<MaterialMovement, UUID> {

    List<MaterialMovement> findByWorkOrderIdOrderByCreatedAtAscIdAsc(UUID workOrderId);

    List<MaterialMovement> findByWorkOrderIdInOrderByCreatedAtAscIdAsc(java.util.Collection<UUID> workOrderIds);

    boolean existsByWorkOrderIdAndRequestKey(UUID workOrderId, String requestKey);
}
