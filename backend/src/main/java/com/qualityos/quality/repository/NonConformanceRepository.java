package com.qualityos.quality.repository;

import com.qualityos.quality.entity.NonConformance;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface NonConformanceRepository
        extends JpaRepository<NonConformance, UUID>, JpaSpecificationExecutor<NonConformance> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from NonConformance n where n.id = :id")
    Optional<NonConformance> findForUpdate(UUID id);

    Optional<NonConformance> findByInspectionId(UUID inspectionId);

    Optional<NonConformance> findByAuditFindingId(UUID auditFindingId);

    Optional<NonConformance> findByCalibrationRecordId(UUID calibrationRecordId);

    Optional<NonConformance> findByOperationReportId(UUID operationReportId);

    Optional<NonConformance> findByDispatchReversalId(UUID dispatchReversalId);

    List<NonConformance> findByDispatchIdOrderByCreatedAt(UUID dispatchId);

    List<NonConformance> findByDispatchReversalIdIn(java.util.Collection<UUID> dispatchReversalIds);

    /** NCRs raised by the inspections of returned goods, with the return each belongs to. */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT n.id AS ncrId, ri.reversal_id AS reversalId FROM non_conformances n
            JOIN inspections i ON i.id = n.inspection_id AND i.tenant_id = n.tenant_id
            JOIN dispatch_reversal_items ri ON ri.id = i.return_item_id AND ri.tenant_id = i.tenant_id
            WHERE ri.reversal_id IN (:reversalIds) ORDER BY n.created_at""", nativeQuery = true)
    List<ReturnInspectionNcr> findRaisedByReturnInspections(java.util.Collection<UUID> reversalIds);

    interface ReturnInspectionNcr {
        UUID getNcrId();

        UUID getReversalId();
    }

    java.util.List<NonConformance> findByCalibrationRecordIdIn(java.util.Collection<UUID> calibrationRecordIds);

    /** NCRs an inspection raised, and those raised by hand against the batch. */
    @Query("""
            select n from NonConformance n
            where n.inspectionId in :inspectionIds or n.batchNumber in :batchNumbers
            order by n.createdAt
            """)
    List<NonConformance> findForTrace(Collection<UUID> inspectionIds, Collection<String> batchNumbers);

    @Query("""
            select n from NonConformance n
            where n.inspectionId in :inspectionIds
              and n.status not in (com.qualityos.quality.entity.NonConformance.Status.CLOSED,
                                   com.qualityos.quality.entity.NonConformance.Status.REJECTED,
                                   com.qualityos.quality.entity.NonConformance.Status.CANCELLED)
            """)
    List<NonConformance> findOpenByInspectionIdIn(Collection<UUID> inspectionIds);
}
