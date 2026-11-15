package com.qualityos.auditmanagement.repository;

import com.qualityos.auditmanagement.entity.QualityAudit;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface QualityAuditRepository
        extends JpaRepository<QualityAudit, UUID>, JpaSpecificationExecutor<QualityAudit> {

    List<QualityAudit> findByStatusAndReminderSentAtIsNullAndPlannedOnBetween(
            QualityAudit.Status status, LocalDate from, LocalDate to);
}
