package com.qualityos.production.repository;

import com.qualityos.production.entity.QualityGate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QualityGateRepository extends JpaRepository<QualityGate, UUID> {

    List<QualityGate> findByWorkOrderIdOrderByCreatedAtAscIdAsc(UUID workOrderId);
}
