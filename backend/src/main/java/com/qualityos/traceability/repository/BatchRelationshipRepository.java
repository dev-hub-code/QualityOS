package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.BatchRelationship;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BatchRelationshipRepository extends JpaRepository<BatchRelationship, UUID> {

    List<BatchRelationship> findByChildBatchIdInOrderByCreatedAt(Collection<UUID> childBatchIds);

    /** The genealogy as it stands, leaving out entries that were reversed. */
    @Query("""
            select r from BatchRelationship r
            where r.childBatchId in :childBatchIds
              and not exists (select 1 from TraceCorrection c where c.batchRelationshipId = r.id)
            order by r.createdAt
            """)
    List<BatchRelationship> findActiveByChildBatchIdIn(Collection<UUID> childBatchIds);
}
