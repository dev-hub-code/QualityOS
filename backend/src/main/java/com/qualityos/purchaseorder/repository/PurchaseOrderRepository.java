package com.qualityos.purchaseorder.repository;

import com.qualityos.purchaseorder.entity.PurchaseOrder;
import com.qualityos.purchaseorder.entity.PurchaseOrderItem;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderRepository
        extends JpaRepository<PurchaseOrder, UUID>, JpaSpecificationExecutor<PurchaseOrder> {

    @EntityGraph(attributePaths = "items")
    @Query("select o from PurchaseOrder o where o.id = :id")
    Optional<PurchaseOrder> findWithItems(UUID id);

    /** Serialises receipts against one order, so that concurrent receipts cannot over-receive a line. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrder o where o.id = :id")
    Optional<PurchaseOrder> findForUpdate(UUID id);

    /** Purchase order lines by id, for their prices. */
    @Query("select i from PurchaseOrderItem i where i.id in :ids")
    List<PurchaseOrderItem> items(@Param("ids") Collection<UUID> ids);

    /** Priced lines of these materials on orders that were issued, for an average price. */
    @Query("""
            select i from PurchaseOrderItem i
            where i.materialId in :materials and i.unitPrice is not null
              and i.purchaseOrder.status not in (com.qualityos.purchaseorder.entity.PurchaseOrder.Status.DRAFT,
                                                 com.qualityos.purchaseorder.entity.PurchaseOrder.Status.CANCELLED)
            """)
    List<PurchaseOrderItem> pricedItems(@Param("materials") Collection<UUID> materials);
}
