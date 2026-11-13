package com.qualityos.production.repository;

import com.qualityos.production.entity.OperationEvent;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OperationEventRepository extends JpaRepository<OperationEvent, UUID> {

    List<OperationEvent> findByOperationIdOrderByCreatedAtAscIdAsc(UUID operationId);

    /** Events of operations at these work centres up to {@code to}, in order, for running time. */
    @Query("""
            SELECT e FROM OperationEvent e WHERE e.operationId IN (
                SELECT o.id FROM WorkOrderOperation o WHERE o.workCenterId IN :centres)
            AND e.createdAt < :to ORDER BY e.operationId, e.createdAt, e.id
            """)
    List<OperationEvent> atCentresBefore(
            @Param("centres") Collection<UUID> centres,
            @Param("to") Instant to);
}
