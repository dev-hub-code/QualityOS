package com.qualityos.auditmanagement.repository;

import com.qualityos.auditmanagement.entity.AuditChecklist;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditChecklistRepository
        extends JpaRepository<AuditChecklist, UUID>, JpaSpecificationExecutor<AuditChecklist> {

    boolean existsByCode(String code);
}
