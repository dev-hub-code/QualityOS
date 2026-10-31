package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.FinishedProduct;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinishedProductRepository extends JpaRepository<FinishedProduct, UUID> {

    List<FinishedProduct> findByProductionBatchIdOrderBySerialNumber(UUID batchId);

    List<FinishedProduct> findByProductionBatchIdInOrderBySerialNumber(Collection<UUID> batchIds);

    List<FinishedProduct> findByDispatchItemIdIn(Collection<UUID> dispatchItemIds);

    long countByProductionBatchId(UUID batchId);

    List<FinishedProduct> findBySerialNumberIn(Collection<String> serialNumbers);

    List<FinishedProduct> findBySerialNumberContainingIgnoreCaseOrderBySerialNumber(String term, Pageable pageable);
}
