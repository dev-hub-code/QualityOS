package com.qualityos.common.attachment;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Entity
@Table(name = "attachments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Attachment extends TenantOwnedEntity {

    private String entityType;
    private UUID entityId;
    private String category;
    private String fileName;
    private String contentType;
    private long sizeBytes;

    @Column(name = "sha256")
    private String sha256;

    private String storageKey;

    @Setter
    private Instant deletedAt;

    Attachment(
            String entityType,
            UUID entityId,
            String category,
            String fileName,
            String contentType,
            long sizeBytes,
            String sha256,
            String storageKey) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.category = category;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storageKey = storageKey;
    }
}
