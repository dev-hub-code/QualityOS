package com.qualityos.organization.repository;

import com.qualityos.organization.entity.ProductionLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductionLineRepository extends JpaRepository<ProductionLine, UUID> {

    List<ProductionLine> findByFacilityIdAndArchivedAtIsNullOrderByName(UUID facilityId);

    boolean existsByFacilityIdAndCode(UUID facilityId, String code);
}
