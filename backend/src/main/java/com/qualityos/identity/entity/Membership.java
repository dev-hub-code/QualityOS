package com.qualityos.identity.entity;

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
@Table(name = "tenant_memberships")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Membership extends TenantOwnedEntity {

    public enum Status { ACTIVE, INACTIVE }

    private UUID userId;

    @Setter
    @Enumerated(EnumType.STRING)
    private Status status = Status.ACTIVE;

    public Membership(UUID userId) {
        this.userId = userId;
    }
}
