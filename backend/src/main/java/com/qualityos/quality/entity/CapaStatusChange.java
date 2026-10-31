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
@Table(name = "capa_status_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CapaStatusChange extends TenantOwnedEntity {

    private UUID capaId;
    private String fromStatus;
    private String toStatus;
    private String note;

    public CapaStatusChange(UUID capaId, String fromStatus, String toStatus, String note) {
        this.capaId = capaId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.note = note;
    }
}
