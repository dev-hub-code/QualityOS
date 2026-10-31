package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.BatchRelationship;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BatchRelationshipRepository extends JpaRepository<BatchRelationship, UUID> {

    List<BatchRelationship> findByChildBatchIdInOrderByCreatedAt(Collection<UUID> childBatchIds);

    boolean existsByChildBatchId(UUID childBatchId);
}
