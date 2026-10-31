package com.qualityos.quality.repository;

import com.qualityos.quality.entity.CorrectiveAction;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CorrectiveActionRepository
        extends JpaRepository<CorrectiveAction, UUID>, JpaSpecificationExecutor<CorrectiveAction> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CorrectiveAction c where c.id = :id")
    Optional<CorrectiveAction> findForUpdate(UUID id);

    List<CorrectiveAction> findByNcrIdOrderByCapaNumber(UUID ncrId);

    /** Skips anyone reminded since {@code remindedBefore}, so that a CAPA does not nag on every run. */
    @Query("""
            select c from CorrectiveAction c
            where c.targetDate < :today
              and c.status not in (com.qualityos.quality.entity.CorrectiveAction.Status.CLOSED,
                                   com.qualityos.quality.entity.CorrectiveAction.Status.CANCELLED)
              and (c.overdueNotifiedAt is null or c.overdueNotifiedAt < :remindedBefore)
            """)
    List<CorrectiveAction> findOverdue(LocalDate today, Instant remindedBefore);
}
