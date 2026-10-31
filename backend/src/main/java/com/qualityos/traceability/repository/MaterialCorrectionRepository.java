package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.MaterialCorrection;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface MaterialCorrectionRepository
        extends JpaRepository<MaterialCorrection, UUID>, JpaSpecificationExecutor<MaterialCorrection> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MaterialCorrection c where c.id = :id")
    Optional<MaterialCorrection> findForUpdate(UUID id);

    @Query("""
            select c from MaterialCorrection c
            where c.receiptItemId = :id or c.productionBatchId = :id
            order by c.createdAt
            """)
    List<MaterialCorrection> findHistory(UUID id);

    @Query("""
            select c from MaterialCorrection c
            where c.status = com.qualityos.traceability.entity.MaterialCorrection.Status.APPLIED
              and (c.receiptItemId in :ids or c.productionBatchId in :ids)
            order by c.appliedAt
            """)
    List<MaterialCorrection> findAppliedFor(Collection<UUID> ids);
}
