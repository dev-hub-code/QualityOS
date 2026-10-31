package com.qualityos.inspection.repository;

import com.qualityos.inspection.entity.Inspection;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface InspectionRepository extends JpaRepository<Inspection, UUID>, JpaSpecificationExecutor<Inspection> {

    /** Workflow steps run one at a time, so that a decision is always made on the current state. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inspection i where i.id = :id")
    Optional<Inspection> findForUpdate(UUID id);

    List<Inspection> findByReceiptItemIdInOrProductionBatchIdInOrderByCreatedAt(
            Collection<UUID> receiptItemIds, Collection<UUID> productionBatchIds);
}
