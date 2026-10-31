package com.qualityos.auditmanagement.repository;

import com.qualityos.auditmanagement.entity.AuditItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditItemRepository extends JpaRepository<AuditItem, UUID> {

    List<AuditItem> findByAuditIdOrderByPosition(UUID auditId);

    Optional<AuditItem> findByIdAndAuditId(UUID id, UUID auditId);
}
