package com.qualityos.production.repository;

import com.qualityos.production.entity.QualityGateInspection;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QualityGateInspectionRepository extends JpaRepository<QualityGateInspection, UUID> {

    List<QualityGateInspection> findByGateIdInOrderByCreatedAtAscIdAsc(Collection<UUID> gateIds);
}
