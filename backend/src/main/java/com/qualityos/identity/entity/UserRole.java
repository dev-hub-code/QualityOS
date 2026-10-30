package com.qualityos.identity.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "user_roles")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserRole extends TenantOwnedEntity {

    private UUID membershipId;
    private UUID roleId;

    public UserRole(UUID membershipId, UUID roleId) {
        this.membershipId = membershipId;
        this.roleId = roleId;
    }
}
