package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.ProductionBatch;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ProductionBatchRepository
        extends JpaRepository<ProductionBatch, UUID>, JpaSpecificationExecutor<ProductionBatch> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ProductionBatch b where b.id = :id")
    Optional<ProductionBatch> findForUpdate(UUID id);

    Optional<ProductionBatch> findByBatchNumber(String batchNumber);

    List<ProductionBatch> findByProductIdInOrderByBatchNumber(Collection<UUID> productIds, Pageable pageable);

    List<ProductionBatch> findByBatchNumberContainingIgnoreCaseOrderByBatchNumber(String term, Pageable pageable);

    List<ProductionBatch> findByStatusOrderByBatchNumber(ProductionBatch.Status status);
}
