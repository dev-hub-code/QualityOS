package com.qualityos.identity.entity;

import com.qualityos.common.persistence.AuditedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Not a {@code TenantOwnedEntity}: system roles have no tenant yet are visible to all, so reads go through
 * {@code RoleRepository}'s explicit queries, with row-level security behind them.
 */
@Getter
@Entity
@Table(name = "roles")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Role extends AuditedEntity {

    public static final String OWNER = "ORGANISATION_OWNER";

    private UUID tenantId;
    private String code;

    @Setter
    private String name;

    @Setter
    private String description;

    @Setter
    @ManyToMany
    @JoinTable(
            name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new HashSet<>();

    public Role(UUID tenantId, String code, String name, String description, Set<Permission> permissions) {
        this.tenantId = tenantId;
        this.code = code;
        this.name = name;
        this.description = description;
        this.permissions = permissions;
    }

    public boolean isSystem() {
        return tenantId == null;
    }
}
