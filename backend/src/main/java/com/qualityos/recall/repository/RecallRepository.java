package com.qualityos.recall.repository;

import com.qualityos.recall.entity.Recall;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RecallRepository extends JpaRepository<Recall, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Recall r where r.id = :id")
    Optional<Recall> findForUpdate(UUID id);

    List<Recall> findByRecallNumberIn(Collection<String> recallNumbers);

    String MATCHING = """
            (lower(r.recallNumber) like :pattern escape '\\'
             or lower(r.reason) like :pattern escape '\\'
             or exists (select 1 from RecallBatch rb, com.qualityos.traceability.entity.ProductionBatch b
                        where rb.recallId = r.id and b.id = rb.productionBatchId
                          and lower(b.batchNumber) like :pattern escape '\\'))
            """;

    /** Recalls whose number, reason or one of whose batch numbers matches. */
    @Query("select r from Recall r where " + MATCHING)
    Page<Recall> findMatching(String pattern, Pageable pageable);

    /** As {@link #findMatching}, lying wholly inside these facilities. A member cannot act on a recall they can only partly see. */
    @Query("""
            select r from Recall r
            where not exists (
                select 1 from RecallBatch rb, com.qualityos.traceability.entity.ProductionBatch b
                where rb.recallId = r.id and b.id = rb.productionBatchId and b.facilityId not in :facilities)
            and """ + MATCHING)
    Page<Recall> findWithin(Collection<UUID> facilities, String pattern, Pageable pageable);
}
