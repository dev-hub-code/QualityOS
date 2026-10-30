package com.qualityos.identity.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.identity.dto.UserRoleDtos.PermissionResponse;
import com.qualityos.identity.dto.UserRoleDtos.RoleResponse;
import com.qualityos.identity.dto.UserRoleDtos.SaveRoleRequest;
import com.qualityos.identity.service.RoleService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Roles")
public class RoleController {

    private static final String CAN_READ =
            "hasAnyAuthority('" + Permissions.ROLE_MANAGE + "', '" + Permissions.USER_MANAGE + "')";
    private static final String CAN_MANAGE = "hasAuthority('" + Permissions.ROLE_MANAGE + "')";

    private final RoleService roles;

    RoleController(RoleService roles) {
        this.roles = roles;
    }

    @GetMapping("/roles")
    @PreAuthorize(CAN_READ)
    public ApiResponse<List<RoleResponse>> list() {
        return ApiResponse.ok(roles.list());
    }

    @GetMapping("/permissions")
    @PreAuthorize(CAN_READ)
    public ApiResponse<List<PermissionResponse>> permissions() {
        return ApiResponse.ok(roles.listPermissions());
    }

    @PostMapping("/roles")
    @PreAuthorize(CAN_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RoleResponse> create(@Valid @RequestBody SaveRoleRequest request) {
        return ApiResponse.ok(roles.create(request));
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize(CAN_MANAGE)
    public ApiResponse<RoleResponse> update(@PathVariable UUID id, @Valid @RequestBody SaveRoleRequest request) {
        return ApiResponse.ok(roles.update(id, request));
    }

    @DeleteMapping("/roles/{id}")
    @PreAuthorize(CAN_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        roles.delete(id);
    }
}
