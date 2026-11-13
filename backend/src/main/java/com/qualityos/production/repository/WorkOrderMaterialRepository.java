package com.qualityos.production.repository;

import com.qualityos.production.entity.WorkOrderMaterial;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderMaterialRepository extends JpaRepository<WorkOrderMaterial, UUID> {

    List<WorkOrderMaterial> findByWorkOrderIdOrderByLineNumber(UUID workOrderId);
}
