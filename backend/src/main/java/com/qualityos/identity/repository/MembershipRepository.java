package com.qualityos.identity.repository;

import com.qualityos.identity.entity.Membership;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    Optional<Membership> findByUserId(UUID userId);

    @Query("""
            select count(m) from Membership m
            where m.status = com.qualityos.identity.entity.Membership.Status.ACTIVE
              and m.id in (select ur.membershipId from UserRole ur where ur.roleId = :roleId)
            """)
    long countActiveWithRole(UUID roleId);
}
