package com.qualityos.traceability.repository;

import com.qualityos.traceability.entity.DispatchRecord;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
public interface DispatchRecordRepository extends JpaRepository<DispatchRecord, UUID> {

    /** Dispatches dated before {@code before} that have neither shipped nor been cancelled. */
    @Query("""
            select count(d) from DispatchRecord d
            where d.dispatchedOn < :before
              and not exists (select 1 from DispatchShipment s where s.dispatchId = d.id)
              and not exists (select 1 from TraceCorrection c where c.dispatchId = d.id)
            """)
    long countUnconfirmed(LocalDate before);

    /** Dispatches with every line in these facilities. One that also carries another plant's goods is left out. */
    @Query("""
            select d from DispatchRecord d
            where not exists (
                select 1 from DispatchItem i, ProductionBatch b
                where i.dispatchId = d.id and b.id = i.productionBatchId and b.facilityId not in :facilities)
            """)
    Page<DispatchRecord> findWithin(Collection<UUID> facilities, Pageable pageable);

    /** Serialises cancelling, shipping and returning, which rule each other out. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DispatchRecord d where d.id = :id")
    Optional<DispatchRecord> findForUpdate(UUID id);
}
