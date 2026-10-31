package com.qualityos.auditmanagement.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "audit_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditItem extends TenantOwnedEntity {

    public enum Result { CONFORMING, NONCONFORMING, NOT_APPLICABLE }

    private UUID auditId;
    private int position;
    private String question;
    private String clauseReference;

    @Enumerated(EnumType.STRING)
    private Result result;

    private String note;

    public AuditItem(UUID auditId, int position, String question, String clauseReference) {
        this.auditId = auditId;
        this.position = position;
        this.question = question;
        this.clauseReference = clauseReference;
    }

    public void answer(Result result, String note) {
        this.result = result;
        this.note = note;
    }
}
