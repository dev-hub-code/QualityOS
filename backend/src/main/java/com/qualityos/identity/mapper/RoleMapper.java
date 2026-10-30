package com.qualityos.identity.mapper;

import com.qualityos.identity.dto.UserRoleDtos.PermissionResponse;
import com.qualityos.identity.dto.UserRoleDtos.RoleResponse;
import com.qualityos.identity.dto.UserRoleDtos.RoleSummary;
import com.qualityos.identity.entity.Permission;
import com.qualityos.identity.entity.Role;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.mapstruct.Mapper;

@Mapper
public interface RoleMapper {

    RoleResponse toResponse(Role role);

    RoleSummary toSummary(Role role);

    PermissionResponse toResponse(Permission permission);

    default Set<String> toCodes(Set<Permission> permissions) {
        return permissions.stream().map(Permission::getCode).collect(Collectors.toCollection(TreeSet::new));
    }
}
