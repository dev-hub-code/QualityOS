package com.qualityos.identity.repository;

import com.qualityos.identity.entity.Role;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    @EntityGraph(attributePaths = "permissions")
    @Query("select r from Role r where r.tenantId is null or r.tenantId = :tenantId order by r.name")
    List<Role> findVisible(UUID tenantId);

    @EntityGraph(attributePaths = "permissions")
    @Query("select r from Role r where r.id in :ids and (r.tenantId is null or r.tenantId = :tenantId)")
    List<Role> findVisibleByIds(Collection<UUID> ids, UUID tenantId);

    @EntityGraph(attributePaths = "permissions")
    @Query("select r from Role r where r.id = :id and (r.tenantId is null or r.tenantId = :tenantId)")
    Optional<Role> findVisibleById(UUID id, UUID tenantId);

    @Query("select r from Role r where r.tenantId is null and r.code = :code")
    Optional<Role> findSystemRole(String code);

    boolean existsByTenantIdAndCode(UUID tenantId, String code);
}
