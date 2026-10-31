package com.qualityos.inspection.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The stable identity of a template. Everything inspected against lives on its versions. */
@Getter
@Setter
@Entity
@Table(name = "inspection_templates")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InspectionTemplate extends TenantOwnedEntity {

    public enum Type { IQC, IPQC, FQC, OQC, SUPPLIER, PACKAGING, VISUAL, DIMENSIONAL, FUNCTIONAL }

    private String code;
    private String name;

    @Enumerated(EnumType.STRING)
    private Type inspectionType;

    private UUID materialId;
    private UUID productId;
    private Instant archivedAt;

    public InspectionTemplate(String code) {
        this.code = code;
    }
}
