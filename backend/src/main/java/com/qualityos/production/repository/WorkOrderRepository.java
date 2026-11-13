package com.qualityos.production.repository;

import com.qualityos.production.entity.WorkOrder;
import java.util.Collection;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, UUID>, JpaSpecificationExecutor<WorkOrder> {

    List<WorkOrder> findByPlanLineIdInOrderByWorkOrderNumber(Collection<UUID> planLineIds);

    /** Holds the order while its stock moves, so two requests for it are applied one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkOrder w where w.id = :id")
    Optional<WorkOrder> findForUpdate(UUID id);

    Optional<WorkOrder> findByProductionBatchId(UUID productionBatchId);

    boolean existsByProductionBatchId(UUID productionBatchId);

    boolean existsByPlanLineIdInAndStatusIn(Collection<UUID> planLineIds, Collection<WorkOrder.Status> statuses);
}
