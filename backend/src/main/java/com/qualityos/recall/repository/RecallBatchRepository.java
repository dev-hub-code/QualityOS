package com.qualityos.recall.repository;

import com.qualityos.recall.entity.RecallBatch;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RecallBatchRepository extends JpaRepository<RecallBatch, UUID> {

    List<RecallBatch> findByRecallIdIn(Collection<UUID> recallIds);

    interface OpenRecall {
        UUID getProductionBatchId();

        String getRecallNumber();
    }

    /** Recalls that are open or awaiting closure, by the batches they started from. */
    @Query("""
            select rb.productionBatchId as productionBatchId, r.recallNumber as recallNumber
            from RecallBatch rb, Recall r
            where r.id = rb.recallId
              and r.status <> com.qualityos.recall.entity.Recall.Status.CLOSED
              and rb.productionBatchId in :batchIds
            """)
    List<OpenRecall> findOpenByProductionBatchIdIn(Collection<UUID> batchIds);
}
