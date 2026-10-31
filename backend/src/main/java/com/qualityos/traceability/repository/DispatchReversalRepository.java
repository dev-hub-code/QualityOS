package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.DispatchReversal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DispatchReversalRepository extends JpaRepository<DispatchReversal, UUID> {

    boolean existsByDispatchId(UUID dispatchId);

    boolean existsByDispatchIdAndReturnReference(UUID dispatchId, String returnReference);

    List<DispatchReversal> findByDispatchIdInOrderByCreatedAt(Collection<UUID> dispatchIds);
}
