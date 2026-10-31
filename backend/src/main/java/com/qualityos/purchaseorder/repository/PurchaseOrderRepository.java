package com.qualityos.purchaseorder.repository;

import com.qualityos.purchaseorder.entity.PurchaseOrder;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PurchaseOrderRepository
        extends JpaRepository<PurchaseOrder, UUID>, JpaSpecificationExecutor<PurchaseOrder> {

    @EntityGraph(attributePaths = "items")
    @Query("select o from PurchaseOrder o where o.id = :id")
    Optional<PurchaseOrder> findWithItems(UUID id);

    /** Serialises receipts against one order, so that concurrent receipts cannot over-receive a line. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrder o where o.id = :id")
    Optional<PurchaseOrder> findForUpdate(UUID id);
}
