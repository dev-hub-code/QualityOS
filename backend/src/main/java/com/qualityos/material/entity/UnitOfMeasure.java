package com.qualityos.material.entity;

import com.qualityos.common.persistence.AuditedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Not a {@code TenantOwnedEntity}: shared defaults have no tenant, so reads use explicit queries. */
@Getter
@Entity
@Table(name = "units_of_measure")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UnitOfMeasure extends AuditedEntity {

    private UUID tenantId;
    private String code;
    private String name;
    private String dimension;

    public UnitOfMeasure(UUID tenantId, String code, String name, String dimension) {
        this.tenantId = tenantId;
        this.code = code;
        this.name = name;
        this.dimension = dimension;
    }
}
