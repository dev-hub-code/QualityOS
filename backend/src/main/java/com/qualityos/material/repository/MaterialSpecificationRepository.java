package com.qualityos.material.repository;

import com.qualityos.material.entity.MaterialSpecification;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialSpecificationRepository extends JpaRepository<MaterialSpecification, UUID> {

    List<MaterialSpecification> findByMaterialIdOrderBySortOrderAscNameAsc(UUID materialId);
}
