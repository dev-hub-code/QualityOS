package com.qualityos.auditmanagement.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "audit_checklists")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditChecklist extends TenantOwnedEntity {

    private String code;
    private String name;
    private Instant archivedAt;

    public AuditChecklist(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
