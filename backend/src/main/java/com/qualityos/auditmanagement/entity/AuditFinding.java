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
import lombok.Setter;

@Getter
@Entity
@Table(name = "audit_findings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditFinding extends TenantOwnedEntity {

    public enum Grade {
        MAJOR_NONCONFORMITY, MINOR_NONCONFORMITY, OBSERVATION, OPPORTUNITY;

        public boolean isNonconformity() {
            return this == MAJOR_NONCONFORMITY || this == MINOR_NONCONFORMITY;
        }
    }

    private UUID auditId;
    private UUID auditItemId;

    @Enumerated(EnumType.STRING)
    private Grade grade;

    private String statement;
    private String evidence;
    private String clauseReference;

    @Setter
    private UUID ncrId;

    public AuditFinding(UUID auditId, UUID auditItemId, Grade grade, String statement, String evidence,
            String clauseReference) {
        this.auditId = auditId;
        this.auditItemId = auditItemId;
        this.grade = grade;
        this.statement = statement;
        this.evidence = evidence;
        this.clauseReference = clauseReference;
    }
}
