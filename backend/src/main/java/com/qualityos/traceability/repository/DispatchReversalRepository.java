package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.DispatchReversal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DispatchReversalRepository extends JpaRepository<DispatchReversal, UUID> {

    boolean existsByDispatchId(UUID dispatchId);

    @Query("""
            select r from DispatchReversal r
            where not exists (
                select 1 from DispatchItem i, ProductionBatch b
                where i.dispatchId = r.dispatchId and b.id = i.productionBatchId and b.facilityId not in :facilities)
            """)
    Page<DispatchReversal> findWithin(Collection<UUID> facilities, Pageable pageable);

    boolean existsByDispatchIdAndReturnReference(UUID dispatchId, String returnReference);

    List<DispatchReversal> findByDispatchIdInOrderByCreatedAt(Collection<UUID> dispatchIds);
}
