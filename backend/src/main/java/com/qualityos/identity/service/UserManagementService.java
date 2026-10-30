package com.qualityos.identity.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.dto.UserRoleDtos.AddMemberRequest;
import com.qualityos.identity.dto.UserRoleDtos.MemberResponse;
import com.qualityos.identity.dto.UserRoleDtos.RoleSummary;
import com.qualityos.identity.entity.Membership;
import com.qualityos.identity.entity.Permission;
import com.qualityos.identity.entity.Role;
import com.qualityos.identity.entity.User;
import com.qualityos.identity.entity.UserRole;
import com.qualityos.identity.mapper.RoleMapper;
import com.qualityos.identity.repository.MembershipRepository;
import com.qualityos.identity.repository.RoleRepository;
import com.qualityos.identity.repository.UserRepository;
import com.qualityos.identity.repository.UserRoleRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserManagementService {

    private final MembershipRepository memberships;
    private final UserRepository users;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final RoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    UserManagementService(
            MembershipRepository memberships,
            UserRepository users,
            RoleRepository roles,
            UserRoleRepository userRoles,
            RoleMapper roleMapper,
            PasswordEncoder passwordEncoder,
            AuditService audit) {
        this.memberships = memberships;
        this.users = users;
        this.roles = roles;
        this.userRoles = userRoles;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<MemberResponse> list(Pageable pageable) {
        Page<Membership> page = memberships.findAll(pageable);
        Map<UUID, User> usersById = users.findAllById(page.map(Membership::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        List<UUID> membershipIds = page.map(Membership::getId).toList();
        List<UserRole> assignments = userRoles.findByMembershipIdIn(membershipIds);
        Set<UUID> roleIds = assignments.stream().map(UserRole::getRoleId).collect(Collectors.toSet());
        Map<UUID, RoleSummary> rolesById = roles.findVisibleByIds(roleIds, TenantContext.getCurrentTenantId()).stream()
                .collect(Collectors.toMap(Role::getId, roleMapper::toSummary));
        Map<UUID, List<RoleSummary>> rolesByMembership = assignments.stream()
                .collect(Collectors.groupingBy(
                        UserRole::getMembershipId,
                        Collectors.mapping(a -> rolesById.get(a.getRoleId()), Collectors.toList())));
        return PageResponse.from(page, m -> toResponse(
                m, usersById.get(m.getUserId()), rolesByMembership.getOrDefault(m.getId(), List.of())));
    }

    @Transactional
    public MemberResponse add(AddMemberRequest request) {
        List<Role> granted = loadGrantable(request.roleIds());
        String email = AuthService.normalizeEmail(request.email());
        User user = users.findByEmail(email).orElseGet(() -> createUser(request, email));
        if (memberships.findByUserId(user.getId()).isPresent()) {
            throw new ConflictException("This user is already a member of the organisation.");
        }
        Membership membership = memberships.saveAndFlush(new Membership(user.getId()));
        granted.forEach(role -> userRoles.save(new UserRole(membership.getId(), role.getId())));
        audit.record("MEMBER_ADDED", "Membership", membership.getId(), null, Map.of("roles", codes(granted)));
        return toResponse(membership, user, granted.stream().map(roleMapper::toSummary).toList());
    }

    @Transactional
    public MemberResponse updateRoles(UUID membershipId, Set<UUID> roleIds) {
        Membership membership = load(membershipId);
        List<Role> granted = loadGrantable(roleIds);
        List<UserRole> current = userRoles.findByMembershipId(membershipId);
        List<Role> previous = roles.findVisibleByIds(
                current.stream().map(UserRole::getRoleId).toList(), TenantContext.getCurrentTenantId());
        boolean losesOwner = hasOwner(previous) && !hasOwner(granted);
        if (losesOwner) {
            requireAnotherOwner();
        }
        userRoles.deleteAllInBatch(current);
        granted.forEach(role -> userRoles.save(new UserRole(membershipId, role.getId())));
        audit.record("MEMBER_ROLES_CHANGED", "Membership", membershipId,
                Map.of("roles", codes(previous)), Map.of("roles", codes(granted)));
        User user = users.findById(membership.getUserId()).orElseThrow();
        return toResponse(membership, user, granted.stream().map(roleMapper::toSummary).toList());
    }

    @Transactional
    public void setActive(UUID membershipId, boolean active) {
        Membership membership = load(membershipId);
        if (!active) {
            if (membership.getUserId().equals(TenantContext.getCurrentUserId())) {
                throw new BusinessRuleException("You cannot deactivate your own membership.");
            }
            List<Role> held = roles.findVisibleByIds(
                    userRoles.findByMembershipId(membershipId).stream().map(UserRole::getRoleId).toList(),
                    TenantContext.getCurrentTenantId());
            if (hasOwner(held) && membership.getStatus() == Membership.Status.ACTIVE) {
                requireAnotherOwner();
            }
        }
        membership.setStatus(active ? Membership.Status.ACTIVE : Membership.Status.INACTIVE);
        audit.record(active ? "MEMBER_ACTIVATED" : "MEMBER_DEACTIVATED", "Membership", membershipId);
    }

    private User createUser(AddMemberRequest request, String email) {
        if (request.initialPassword() == null) {
            throw new BusinessRuleException("An initial password is required for a new user.");
        }
        return users.saveAndFlush(
                new User(email, passwordEncoder.encode(request.initialPassword()), request.fullName().trim()));
    }

    private Membership load(UUID id) {
        return memberships.findById(id).orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    private List<Role> loadGrantable(Collection<UUID> roleIds) {
        List<Role> found = roles.findVisibleByIds(roleIds, TenantContext.getCurrentTenantId());
        if (found.size() != roleIds.size()) {
            throw new BusinessRuleException("One or more roles do not exist.");
        }
        AuthorityChecks.requireHolds(found.stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(Permission::getCode)
                .collect(Collectors.toSet()));
        return found;
    }

    private void requireAnotherOwner() {
        Role owner = roles.findSystemRole(Role.OWNER).orElseThrow();
        if (memberships.countActiveWithRole(owner.getId()) <= 1) {
            throw new BusinessRuleException("An organisation must keep at least one active owner.");
        }
    }

    private static boolean hasOwner(List<Role> roles) {
        return roles.stream().anyMatch(role -> Role.OWNER.equals(role.getCode()));
    }

    private static List<String> codes(List<Role> roles) {
        return roles.stream().map(Role::getCode).toList();
    }

    private static MemberResponse toResponse(Membership membership, User user, List<RoleSummary> roles) {
        return new MemberResponse(
                membership.getId(),
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                membership.getStatus().name(),
                roles);
    }
}
