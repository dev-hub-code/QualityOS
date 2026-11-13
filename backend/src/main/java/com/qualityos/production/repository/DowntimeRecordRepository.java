package com.qualityos.production.repository;

import com.qualityos.production.entity.DowntimeRecord;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DowntimeRecordRepository
        extends JpaRepository<DowntimeRecord, UUID>, JpaSpecificationExecutor<DowntimeRecord> {

    Optional<DowntimeRecord> findByOperationIdAndEndedAtIsNull(UUID operationId);

    Optional<DowntimeRecord> findByMachineIdAndSourceAndEndedAtIsNull(UUID machineId, DowntimeRecord.Source source);

    List<DowntimeRecord> findByWorkOrderIdOrderByStartedAtAsc(UUID workOrderId);

    /** Stops that overlap [from, to), open ones included, in the given facilities. */
    @Query("""
            SELECT d FROM DowntimeRecord d
            WHERE d.facilityId IN :facilities AND d.startedAt < :to AND (d.endedAt IS NULL OR d.endedAt > :from)
            ORDER BY d.startedAt
            """)
    List<DowntimeRecord> overlapping(@Param("facilities") Collection<UUID> facilities, @Param("from") Instant from,
            @Param("to") Instant to);
}
