package com.qualityos.common.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.TenantId;

/** Hibernate filters reads by, and fills in, the tenant of the current {@code TenantContext}. */
@Getter
@MappedSuperclass
public abstract class TenantOwnedEntity extends AuditedEntity {

    @TenantId
    @Column(nullable = false, updatable = false)
    private UUID tenantId;
}
