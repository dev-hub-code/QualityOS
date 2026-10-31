package com.qualityos.identity.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.dto.UserRoleDtos.PermissionResponse;
import com.qualityos.identity.dto.UserRoleDtos.RoleResponse;
import com.qualityos.identity.dto.UserRoleDtos.SaveRoleRequest;
import com.qualityos.identity.entity.Permission;
import com.qualityos.identity.entity.Role;
import com.qualityos.identity.mapper.RoleMapper;
import com.qualityos.identity.repository.PermissionRepository;
import com.qualityos.identity.repository.RoleRepository;
import com.qualityos.identity.repository.UserRoleRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleService {

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final UserRoleRepository userRoles;
    private final RoleMapper mapper;
    private final AuditService audit;
    private final TenantAccessService access;

    RoleService(
            RoleRepository roles,
            PermissionRepository permissions,
            UserRoleRepository userRoles,
            RoleMapper mapper,
            AuditService audit,
            TenantAccessService access) {
        this.roles = roles;
        this.permissions = permissions;
        this.userRoles = userRoles;
        this.mapper = mapper;
        this.audit = audit;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> list() {
        return roles.findVisible(TenantContext.getCurrentTenantId()).stream().map(mapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<PermissionResponse> listPermissions() {
        return permissions.findAll(Sort.by("module", "code")).stream().map(mapper::toResponse).toList();
    }

    @Transactional
    public RoleResponse create(SaveRoleRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String code = toCode(request.name());
        if (roles.existsByTenantIdAndCode(tenantId, code)) {
            throw new ConflictException("A role with this name already exists.");
        }
        Role role = new Role(tenantId, code, request.name().trim(), request.description(), resolve(request.permissions()));
        roles.saveAndFlush(role);
        audit.record("ROLE_CREATED", "Role", role.getId(), null, Map.of("permissions", request.permissions()));
        return mapper.toResponse(role);
    }

    @Transactional
    public RoleResponse update(UUID id, SaveRoleRequest request) {
        Role role = loadCustom(id);
        Set<String> previous = mapper.toCodes(role.getPermissions());
        role.setName(request.name().trim());
        role.setDescription(request.description());
        role.setPermissions(resolve(request.permissions()));
        roles.saveAndFlush(role);
        audit.record("ROLE_PERMISSIONS_CHANGED", "Role", id,
                Map.of("permissions", previous), Map.of("permissions", request.permissions()));
        return mapper.toResponse(role);
    }

    @Transactional
    public void delete(UUID id) {
        Role role = loadCustom(id);
        if (userRoles.existsByRoleId(id)) {
            throw new BusinessRuleException("This role is assigned to users. Remove it from them first.");
        }
        roles.delete(role);
        audit.record("ROLE_DELETED", "Role", id);
    }

    private Role loadCustom(UUID id) {
        Role role = roles.findVisibleById(id, TenantContext.getCurrentTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Role", id));
        if (role.isSystem()) {
            throw new BusinessRuleException("System roles cannot be changed.");
        }
        return role;
    }

    private Set<Permission> resolve(Set<String> codes) {
        AuthorityChecks.requireHolds(codes, access.rolePermissions());
        List<Permission> found = permissions.findByCodeIn(codes);
        if (found.size() != codes.size()) {
            throw new BusinessRuleException("One or more permissions do not exist.");
        }
        return new HashSet<>(found);
    }

    private static String toCode(String name) {
        return name.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }
}
