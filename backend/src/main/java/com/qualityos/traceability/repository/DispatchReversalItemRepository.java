package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.DispatchReversalItem;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DispatchReversalItemRepository extends JpaRepository<DispatchReversalItem, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from DispatchReversalItem i where i.id = :id")
    Optional<DispatchReversalItem> findForUpdate(UUID id);

    List<DispatchReversalItem> findByReversalIdIn(Collection<UUID> reversalIds);

    List<DispatchReversalItem> findByDispatchItemIdIn(Collection<UUID> dispatchItemIds);
}
