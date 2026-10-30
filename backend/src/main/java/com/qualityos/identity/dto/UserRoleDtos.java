package com.qualityos.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class UserRoleDtos {

    private UserRoleDtos() {
    }

    /** @param initialPassword used only when no account exists yet for this email */
    public record AddMemberRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 200) String fullName,
            @Size(min = 10, max = 128) String initialPassword,
            @NotEmpty Set<@NotNull UUID> roleIds) {

        @Override
        public String toString() {
            return "AddMemberRequest[" + email + "]";
        }
    }

    public record UpdateMemberRolesRequest(@NotEmpty Set<@NotNull UUID> roleIds) {
    }

    public record RoleSummary(UUID id, String code, String name) {
    }

    public record MemberResponse(
            UUID membershipId,
            UUID userId,
            String email,
            String fullName,
            String status,
            List<RoleSummary> roles) {
    }

    public record SaveRoleRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotEmpty Set<@NotBlank String> permissions) {
    }

    public record RoleResponse(
            UUID id,
            String code,
            String name,
            String description,
            boolean system,
            Set<String> permissions) {
    }

    public record PermissionResponse(String code, String module, String description) {
    }
}
