package com.qualityos.identity.repository;

import com.qualityos.identity.entity.UserRole;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleRepository extends JpaRepository<UserRole, UUID> {

    List<UserRole> findByMembershipIdIn(Collection<UUID> membershipIds);

    List<UserRole> findByMembershipId(UUID membershipId);

    boolean existsByRoleId(UUID roleId);
}
