package com.qualityos.production.repository;

import com.qualityos.production.entity.ReworkOrder;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ReworkOrderRepository extends JpaRepository<ReworkOrder, UUID>, JpaSpecificationExecutor<ReworkOrder> {

    List<ReworkOrder> findByWorkOrderIdOrderByCreatedAtAscIdAsc(UUID workOrderId);

    List<ReworkOrder> findByOperationId(UUID operationId);

    /** Without loading the rework order, so that it is read only once its work order is locked. */
    @Query("select r.workOrderId from ReworkOrder r where r.id = :id")
    Optional<UUID> workOrderIdOf(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReworkOrder r where r.id = :id")
    Optional<ReworkOrder> findForUpdate(UUID id);
}
