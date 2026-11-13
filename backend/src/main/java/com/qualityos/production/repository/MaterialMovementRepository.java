package com.qualityos.production.repository;

import com.qualityos.production.entity.MaterialMovement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialMovementRepository extends JpaRepository<MaterialMovement, UUID> {

    List<MaterialMovement> findByWorkOrderIdOrderByCreatedAtAscIdAsc(UUID workOrderId);

    boolean existsByWorkOrderIdAndRequestKey(UUID workOrderId, String requestKey);
}
