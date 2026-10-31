package com.qualityos.materialreceipt.repository;

import jakarta.persistence.LockModeType;
import com.qualityos.materialreceipt.entity.MaterialReceiptItem;
import com.qualityos.materialreceipt.entity.MaterialReceiptItem.InspectionStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface MaterialReceiptItemRepository extends JpaRepository<MaterialReceiptItem, UUID> {

    @EntityGraph(attributePaths = "receipt")
    Page<MaterialReceiptItem> findByInspectionStatus(InspectionStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "receipt")
    Page<MaterialReceiptItem> findByInspectionStatusAndReceiptFacilityIdIn(
            InspectionStatus status, Collection<UUID> facilities, Pageable pageable);

    @EntityGraph(attributePaths = "receipt")
    @Query("select i from MaterialReceiptItem i where i.id = :id")
    Optional<MaterialReceiptItem> findWithReceipt(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from MaterialReceiptItem i join fetch i.receipt where i.id = :id")
    Optional<MaterialReceiptItem> findForUpdate(UUID id);

    @EntityGraph(attributePaths = "receipt")
    Optional<MaterialReceiptItem> findByInternalBatch(String internalBatch);

    @EntityGraph(attributePaths = "receipt")
    List<MaterialReceiptItem> findByIdIn(Collection<UUID> ids);

    @Query("""
            select i from MaterialReceiptItem i join fetch i.receipt r
            where r.status <> com.qualityos.materialreceipt.entity.MaterialReceipt.Status.VOIDED
              and (:materialId is null or i.materialId = :materialId)
            order by r.receivedOn, i.internalBatch
            """)
    List<MaterialReceiptItem> findLots(UUID materialId);

    @Query("""
            select i from MaterialReceiptItem i join fetch i.receipt r
            where lower(i.supplierBatch) like :pattern or lower(i.internalBatch) like :pattern
               or lower(r.grnNumber) like :pattern or i.materialId in :materialIds
            order by i.internalBatch
            """)
    List<MaterialReceiptItem> search(String pattern, Collection<UUID> materialIds, Pageable pageable);
}
