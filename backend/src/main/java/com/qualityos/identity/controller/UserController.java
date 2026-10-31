package com.qualityos.identity.controller;

import com.qualityos.common.response.ApiResponse;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.security.Permissions;
import com.qualityos.identity.dto.UserRoleDtos.MemberResponse;
import com.qualityos.identity.dto.UserRoleDtos.UpdateMemberFacilitiesRequest;
import com.qualityos.identity.dto.UserRoleDtos.UpdateMemberRolesRequest;
import com.qualityos.identity.service.UserManagementService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users")
@PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE + "')")
public class UserController {

    private final UserManagementService users;

    UserController(UserManagementService users) {
        this.users = users;
    }

    @GetMapping
    public ApiResponse<PageResponse<MemberResponse>> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(users.list(PageRequest.of(page, size, Sort.by("createdAt"))));
    }

    @PutMapping("/{membershipId}/roles")
    public ApiResponse<MemberResponse> updateRoles(
            @PathVariable UUID membershipId, @Valid @RequestBody UpdateMemberRolesRequest request) {
        return ApiResponse.ok(users.updateRoles(membershipId, request.roleIds()));
    }

    @PutMapping("/{membershipId}/facilities")
    public ApiResponse<MemberResponse> updateFacilities(
            @PathVariable UUID membershipId, @Valid @RequestBody UpdateMemberFacilitiesRequest request) {
        return ApiResponse.ok(users.updateFacilities(membershipId, request.facilityIds()));
    }

    @PostMapping("/{membershipId}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID membershipId) {
        users.setActive(membershipId, false);
    }

    @PostMapping("/{membershipId}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(@PathVariable UUID membershipId) {
        users.setActive(membershipId, true);
    }
}
