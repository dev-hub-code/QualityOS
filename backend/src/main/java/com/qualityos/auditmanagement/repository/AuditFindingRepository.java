package com.qualityos.auditmanagement.repository;

import com.qualityos.auditmanagement.entity.AuditFinding;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditFindingRepository extends JpaRepository<AuditFinding, UUID> {

    List<AuditFinding> findByAuditIdOrderByCreatedAt(UUID auditId);

    Optional<AuditFinding> findByIdAndAuditId(UUID id, UUID auditId);
}
