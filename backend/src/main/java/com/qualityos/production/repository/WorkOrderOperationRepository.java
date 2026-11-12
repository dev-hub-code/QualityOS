package com.qualityos.production.repository;

import com.qualityos.production.entity.WorkOrderOperation;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderOperationRepository extends JpaRepository<WorkOrderOperation, UUID> {

    List<WorkOrderOperation> findByWorkOrderIdOrderBySequence(UUID workOrderId);
}
