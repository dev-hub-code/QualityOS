package com.qualityos.production.repository;

import com.qualityos.production.entity.OperationReport;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperationReportRepository extends JpaRepository<OperationReport, UUID> {

    List<OperationReport> findByOperationIdOrderByCreatedAtAscIdAsc(UUID operationId);

    boolean existsByOperationIdAndRequestKey(UUID operationId, String requestKey);

    boolean existsByReversesReportId(UUID reportId);

    /** Reports made in [from, to) on operations at these work centres, reversals included. */
    @Query("""
            SELECT r FROM OperationReport r WHERE r.operationId IN (
                SELECT o.id FROM WorkOrderOperation o WHERE o.workCenterId IN :centres)
            AND r.createdAt >= :from AND r.createdAt < :to
            """)
    List<OperationReport> atCentresBetween(
            @Param("centres") Collection<UUID> centres,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
