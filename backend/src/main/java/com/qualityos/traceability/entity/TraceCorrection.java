package com.qualityos.traceability.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Reverses one consumption or one dispatch. Append-only: the database refuses any change. */
@Getter
@Entity
@Table(name = "trace_corrections")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TraceCorrection extends TenantOwnedEntity {

    private UUID batchRelationshipId;
    private UUID dispatchId;
    private String reason;

    private TraceCorrection(UUID batchRelationshipId, UUID dispatchId, String reason) {
        this.batchRelationshipId = batchRelationshipId;
        this.dispatchId = dispatchId;
        this.reason = reason;
    }

    public static TraceCorrection ofConsumption(UUID relationshipId, String reason) {
        return new TraceCorrection(relationshipId, null, reason);
    }

    public static TraceCorrection ofDispatch(UUID dispatchId, String reason) {
        return new TraceCorrection(null, dispatchId, reason);
    }
}
