package com.qualityos.production.repository;

import com.qualityos.production.entity.WorkOrderStatusChange;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderStatusChangeRepository extends JpaRepository<WorkOrderStatusChange, UUID> {

    List<WorkOrderStatusChange> findByWorkOrderIdOrderByCreatedAtAscIdAsc(UUID workOrderId);
}
