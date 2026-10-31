package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.TraceCorrection;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TraceCorrectionRepository extends JpaRepository<TraceCorrection, UUID> {

    boolean existsByBatchRelationshipId(UUID relationshipId);

    boolean existsByDispatchId(UUID dispatchId);

    List<TraceCorrection> findByBatchRelationshipIdIn(Collection<UUID> relationshipIds);

    List<TraceCorrection> findByDispatchIdIn(Collection<UUID> dispatchIds);
}
