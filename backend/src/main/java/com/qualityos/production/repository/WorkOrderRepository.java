package com.qualityos.production.repository;

import com.qualityos.production.entity.WorkOrder;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, UUID>, JpaSpecificationExecutor<WorkOrder> {

    List<WorkOrder> findByPlanLineIdInOrderByWorkOrderNumber(Collection<UUID> planLineIds);

    boolean existsByPlanLineIdInAndStatusIn(Collection<UUID> planLineIds, Collection<WorkOrder.Status> statuses);
}
