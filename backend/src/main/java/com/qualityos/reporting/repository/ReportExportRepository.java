package com.qualityos.reporting.repository;

import com.qualityos.reporting.entity.ReportExport;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportExportRepository extends JpaRepository<ReportExport, UUID> {

    List<ReportExport> findTop50ByRequestedByOrderByCreatedAtDesc(UUID requestedBy);

    long countByRequestedByAndStatusIn(UUID requestedBy, Collection<ReportExport.Status> statuses);

    List<ReportExport> findByStatusAndCreatedAtBefore(ReportExport.Status status, Instant before);

    List<ReportExport> findByStatusAndStartedAtBefore(ReportExport.Status status, Instant before);

    List<ReportExport> findByStatusAndExpiresAtBefore(ReportExport.Status status, Instant before);
}
