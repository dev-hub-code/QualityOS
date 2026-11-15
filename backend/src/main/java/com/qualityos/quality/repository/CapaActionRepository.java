package com.qualityos.quality.repository;

import com.qualityos.quality.entity.CapaAction;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CapaActionRepository extends JpaRepository<CapaAction, UUID> {

    List<CapaAction> findByCapaIdOrderByCreatedAt(UUID capaId);

    Optional<CapaAction> findByIdAndCapaId(UUID id, UUID capaId);

    /** Open actions of open CAPAs past their date, not reminded since the given time. */
    @Query("""
            select a from CapaAction a
            where a.completedAt is null and a.targetDate < :today
              and (a.overdueNotifiedAt is null or a.overdueNotifiedAt < :remindedBefore)
              and exists (select c from CorrectiveAction c where c.id = a.capaId
                          and c.status not in (com.qualityos.quality.entity.CorrectiveAction.Status.CLOSED,
                                               com.qualityos.quality.entity.CorrectiveAction.Status.CANCELLED))
            order by a.targetDate
            """)
    List<CapaAction> findOverdue(LocalDate today, Instant remindedBefore);
}
