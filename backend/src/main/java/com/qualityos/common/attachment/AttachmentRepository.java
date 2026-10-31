package com.qualityos.common.attachment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    List<Attachment> findByEntityTypeAndEntityIdAndDeletedAtIsNullOrderByCreatedAtDesc(String entityType, UUID entityId);

    Optional<Attachment> findByIdAndDeletedAtIsNull(UUID id);
}
