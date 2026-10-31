package com.qualityos.recall.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "recall_batches")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecallBatch extends TenantOwnedEntity {

    private UUID recallId;
    private UUID productionBatchId;

    public RecallBatch(UUID recallId, UUID productionBatchId) {
        this.recallId = recallId;
        this.productionBatchId = productionBatchId;
    }
}
