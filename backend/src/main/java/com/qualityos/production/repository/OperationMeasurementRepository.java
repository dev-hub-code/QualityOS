package com.qualityos.production.repository;

import com.qualityos.production.entity.OperationMeasurement;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationMeasurementRepository extends JpaRepository<OperationMeasurement, UUID> {

    List<OperationMeasurement> findByOperationIdOrderByCreatedAtAscIdAsc(UUID operationId);

    boolean existsByOperationIdAndRequestKey(UUID operationId, String requestKey);
}
