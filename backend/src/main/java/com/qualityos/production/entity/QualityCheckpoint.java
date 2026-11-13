package com.qualityos.production.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import lombok.Setter;

/**
 * Where production needs an inspection. Product, work centre and operation sequence each narrow where it applies;
 * left empty, it applies everywhere. Released work orders keep the checkpoints they took.
 */
@Getter
@Setter
@Entity
@Table(name = "quality_checkpoints")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QualityCheckpoint extends TenantOwnedEntity {

    public enum Stage {
        OPERATION_START, IN_PROCESS, OPERATION_COMPLETION, FINAL_RELEASE;

        /** Whether it belongs to an operation rather than to the order's batch as a whole. */
        public boolean onOperation() {
            return this != FINAL_RELEASE;
        }
    }

    private String name;

    @Enumerated(EnumType.STRING)
    private Stage stage;

    private UUID templateId;
    private UUID productId;
    private UUID workCenterId;
    private Integer operationSequence;
    private boolean mandatory = true;
    private Instant archivedAt;

    public QualityCheckpoint(Stage stage) {
        this.stage = stage;
    }

    /** Whether an operation of an order for this product, at this work centre and sequence, takes it. */
    public boolean appliesTo(UUID product, UUID workCenter, Integer sequence) {
        return (productId == null || productId.equals(product))
                && (workCenterId == null || workCenterId.equals(workCenter))
                && (operationSequence == null || operationSequence.equals(sequence));
    }
}
