package com.qualityos.identity.service;

import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.exception.QualityOsException;
import com.qualityos.identity.entity.Membership;
import com.qualityos.identity.entity.Role;
import com.qualityos.identity.entity.UserRole;
import com.qualityos.identity.repository.MembershipRepository;
import com.qualityos.identity.repository.RoleRepository;
import com.qualityos.identity.repository.UserRoleRepository;
import com.qualityos.organization.service.OrganizationService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrganizationProvisioningService {

    private final OrganizationService organizations;
    private final MembershipRepository memberships;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;

    OrganizationProvisioningService(
            OrganizationService organizations,
            MembershipRepository memberships,
            RoleRepository roles,
            UserRoleRepository userRoles) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.roles = roles;
        this.userRoles = userRoles;
    }

    /** Runs inside a transaction whose tenant context is already {@code tenantId}. */
    @Transactional(propagation = Propagation.MANDATORY)
    void provision(UUID tenantId, UUID ownerUserId, String name) {
        organizations.create(tenantId, name);
        Role owner = roles.findSystemRole(Role.OWNER).orElseThrow(() ->
                new QualityOsException(ErrorCode.INTERNAL_ERROR, "Owner role is not seeded"));
        Membership membership = memberships.saveAndFlush(new Membership(ownerUserId));
        userRoles.save(new UserRole(membership.getId(), owner.getId()));
    }
}
