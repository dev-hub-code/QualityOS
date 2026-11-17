package com.qualityos.recall.repository;

import com.qualityos.recall.entity.RecallContact;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecallContactRepository extends JpaRepository<RecallContact, UUID> {

    List<RecallContact> findByRecallId(UUID recallId);

    /** Customers of open recalls not yet settled or escalated; whether they acknowledged is checked by the caller. */
    @org.springframework.data.jpa.repository.Query("""
            select c from RecallContact c
            where c.escalatedAt is null and c.exceptionAt is null
              and c.status <> com.qualityos.recall.entity.RecallContact.Status.RESOLVED
              and exists (select r from Recall r where r.id = c.recallId
                          and r.status = com.qualityos.recall.entity.Recall.Status.OPEN)
            """)
    List<RecallContact> findEscalationCandidates();

    Optional<RecallContact> findByRecallIdAndCustomerId(UUID recallId, UUID customerId);
}
