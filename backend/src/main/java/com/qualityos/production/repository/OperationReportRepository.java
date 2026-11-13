package com.qualityos.production.repository;

import com.qualityos.production.entity.OperationReport;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationReportRepository extends JpaRepository<OperationReport, UUID> {

    List<OperationReport> findByOperationIdOrderByCreatedAtAscIdAsc(UUID operationId);

    boolean existsByOperationIdAndRequestKey(UUID operationId, String requestKey);

    boolean existsByReversesReportId(UUID reportId);
}
