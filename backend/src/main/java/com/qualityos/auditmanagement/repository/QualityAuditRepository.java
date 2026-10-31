package com.qualityos.auditmanagement.repository;

import com.qualityos.auditmanagement.entity.QualityAudit;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface QualityAuditRepository
        extends JpaRepository<QualityAudit, UUID>, JpaSpecificationExecutor<QualityAudit> {
}
