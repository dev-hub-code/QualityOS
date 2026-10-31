package com.qualityos.calibration.repository;

import com.qualityos.calibration.entity.Instrument;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface InstrumentRepository extends JpaRepository<Instrument, UUID>, JpaSpecificationExecutor<Instrument> {

    boolean existsByInstrumentCode(String instrumentCode);

    @Query("""
            SELECT i FROM Instrument i WHERE i.status = ACTIVE AND i.nextDueOn < :today
            AND (i.overdueNotifiedAt IS NULL OR i.overdueNotifiedAt < :notifiedBefore)
            """)
    List<Instrument> findOverdue(LocalDate today, Instant notifiedBefore);

    @Query("""
            SELECT i FROM Instrument i WHERE i.status = ACTIVE AND i.nextDueOn >= :today AND i.nextDueOn <= :until
            AND i.dueRemindedAt IS NULL
            """)
    List<Instrument> findDueSoon(LocalDate today, LocalDate until);
}
