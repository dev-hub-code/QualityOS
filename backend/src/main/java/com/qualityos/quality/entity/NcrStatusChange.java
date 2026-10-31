package com.qualityos.quality.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Append-only: the database refuses any change. */
@Getter
@Entity
@Table(name = "ncr_status_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NcrStatusChange extends TenantOwnedEntity {

    private UUID ncrId;
    private String fromStatus;
    private String toStatus;
    private String note;

    public NcrStatusChange(UUID ncrId, String fromStatus, String toStatus, String note) {
        this.ncrId = ncrId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.note = note;
    }
}
