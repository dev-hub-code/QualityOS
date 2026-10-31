package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.DispatchItem;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DispatchItemRepository extends JpaRepository<DispatchItem, UUID> {

    List<DispatchItem> findByProductionBatchIdIn(Collection<UUID> batchIds);

    List<DispatchItem> findByDispatchIdIn(Collection<UUID> dispatchIds);
}
