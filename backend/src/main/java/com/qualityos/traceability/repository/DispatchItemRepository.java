package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.DispatchItem;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DispatchItemRepository extends JpaRepository<DispatchItem, UUID> {

    List<DispatchItem> findByProductionBatchIdIn(Collection<UUID> batchIds);

    List<DispatchItem> findByDispatchIdIn(Collection<UUID> dispatchIds);

    @Query("""
            select distinct b.facilityId from DispatchItem i, ProductionBatch b
            where i.dispatchId = :dispatchId and b.id = i.productionBatchId
            """)
    Set<UUID> facilitiesOf(UUID dispatchId);
}
