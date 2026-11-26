package com.qualityos.quality.repository;

import com.qualityos.quality.entity.CorrectiveAction;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
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

    /** The CAPAs an NCR answers to: those raised from it, and existing ones it was linked to. */
    @Query(value = """
            SELECT c.* FROM corrective_actions c
            WHERE c.ncr_id = :ncrId OR c.id IN (SELECT l.capa_id FROM ncr_capa_links l WHERE l.ncr_id = :ncrId)
            ORDER BY c.capa_number""", nativeQuery = true)
    List<CorrectiveAction> findAnsweringNcr(UUID ncrId);

    @org.springframework.data.jpa.repository.Modifying
    @Query(value = """
            INSERT INTO ncr_capa_links (tenant_id, ncr_id, capa_id, note, linked_by)
            VALUES (current_tenant_id(), :ncrId, :capaId, :note, :linkedBy) ON CONFLICT DO NOTHING""", nativeQuery = true)
    int linkToNcr(UUID ncrId, UUID capaId, String note, UUID linkedBy);

    /** Why each existing CAPA was linked to this NCR: capa id, note. */
    @Query(value = "SELECT l.capa_id, l.note FROM ncr_capa_links l WHERE l.ncr_id = :ncrId", nativeQuery = true)
    List<Object[]> linkNotesOfNcr(UUID ncrId);

    /** The NCRs linked to this CAPA after it was raised, and why: ncr id, number, status, note. */
    @Query(value = """
            SELECT n.id, n.ncr_number, n.status, l.note FROM ncr_capa_links l
            JOIN non_conformances n ON n.tenant_id = l.tenant_id AND n.id = l.ncr_id
            WHERE l.capa_id = :capaId ORDER BY n.ncr_number""", nativeQuery = true)
    List<Object[]> linkedNcrsOfCapa(UUID capaId);

    List<CorrectiveAction> findByNcrIdInOrderByCreatedAt(Collection<UUID> ncrIds);

    /** Skips anyone reminded since {@code remindedBefore}, so that a CAPA does not nag on every run. */
    @Query("""
            select c from CorrectiveAction c
            where c.targetDate < :today
              and c.status not in (com.qualityos.quality.entity.CorrectiveAction.Status.CLOSED,
                                   com.qualityos.quality.entity.CorrectiveAction.Status.CANCELLED)
              and (c.overdueNotifiedAt is null or c.overdueNotifiedAt < :remindedBefore)
            """)
    List<CorrectiveAction> findOverdue(LocalDate today, Instant remindedBefore);

    /** Open CAPAs due on or before the given date, not yet escalated. */
    @Query("""
            select c from CorrectiveAction c
            where c.targetDate <= :dueBy and c.escalatedAt is null
              and c.status not in (com.qualityos.quality.entity.CorrectiveAction.Status.CLOSED,
                                   com.qualityos.quality.entity.CorrectiveAction.Status.CANCELLED)
            """)
    List<CorrectiveAction> findToEscalate(LocalDate dueBy);

    @Query("""
            select c from CorrectiveAction c
            where c.targetDate between :today and :until
              and c.status not in (com.qualityos.quality.entity.CorrectiveAction.Status.CLOSED,
                                   com.qualityos.quality.entity.CorrectiveAction.Status.CANCELLED)
              and c.dueRemindedAt is null
            """)
    List<CorrectiveAction> findDueSoon(LocalDate today, LocalDate until);
}
