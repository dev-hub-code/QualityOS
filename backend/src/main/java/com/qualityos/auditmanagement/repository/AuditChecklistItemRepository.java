package com.qualityos.auditmanagement.repository;

import com.qualityos.auditmanagement.entity.AuditChecklistItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditChecklistItemRepository extends JpaRepository<AuditChecklistItem, UUID> {

    List<AuditChecklistItem> findByChecklistIdOrderByPosition(UUID checklistId);

    void deleteByChecklistId(UUID checklistId);
}
