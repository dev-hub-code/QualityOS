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
@Table(name = "capa_verifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CapaVerification extends TenantOwnedEntity {

    private UUID capaId;
    private boolean effective;
    private String method;
    private String note;

    public CapaVerification(UUID capaId, boolean effective, String method, String note) {
        this.capaId = capaId;
        this.effective = effective;
        this.method = method;
        this.note = note;
    }
}
