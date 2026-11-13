package com.qualityos.production.repository;

import com.qualityos.production.entity.WorkOrder;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, UUID>, JpaSpecificationExecutor<WorkOrder> {

    List<WorkOrder> findByPlanLineIdInOrderByWorkOrderNumber(Collection<UUID> planLineIds);

    /** Holds the order while its stock moves, so two requests for it are applied one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WorkOrder w where w.id = :id")
    Optional<WorkOrder> findForUpdate(UUID id);

    Optional<WorkOrder> findByProductionBatchId(UUID productionBatchId);

    boolean existsByProductionBatchId(UUID productionBatchId);

    boolean existsByPlanLineIdInAndStatusIn(Collection<UUID> planLineIds, Collection<WorkOrder.Status> statuses);

    /** Open orders of a facility planned to start by the given day, for the scheduling board. */
    List<WorkOrder> findByFacilityIdAndStatusInAndPlannedStartLessThanEqual(UUID facilityId,
            Collection<WorkOrder.Status> statuses, LocalDate start);

    List<WorkOrder> findByStatusInAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
            Collection<WorkOrder.Status> statuses, Instant from, Instant to);

    /** How many orders of these facilities are in each status: rows of status and count. */
    @Query("select w.status, count(w) from WorkOrder w where w.facilityId in :facilities group by w.status")
    List<Object[]> statusCounts(@Param("facilities") Collection<UUID> facilities);
}
