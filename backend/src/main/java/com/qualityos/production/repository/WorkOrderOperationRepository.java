package com.qualityos.production.repository;

import com.qualityos.production.entity.WorkOrderOperation;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface WorkOrderOperationRepository
        extends JpaRepository<WorkOrderOperation, UUID>, JpaSpecificationExecutor<WorkOrderOperation> {

    List<WorkOrderOperation> findByWorkOrderIdOrderBySequence(UUID workOrderId);

    List<WorkOrderOperation> findByWorkOrderIdInOrderBySequence(java.util.Collection<UUID> workOrderIds);

    /** Without loading the operation, so that it is read only once its order is locked. */
    @Query("select o.workOrderId from WorkOrderOperation o where o.id = :id")
    java.util.Optional<UUID> workOrderIdOf(UUID id);

    @Query("""
            select count(o) > 0 from WorkOrderOperation o
            where o.machineId = :machineId and o.status = com.qualityos.production.entity.WorkOrderOperation.Status.IN_PROGRESS
            and o.id <> :exceptId
            """)
    boolean machineBusy(UUID machineId, UUID exceptId);

    List<WorkOrderOperation> findByWorkCenterIdInAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(
            Collection<UUID> workCenterIds, Instant from, Instant to);
}
