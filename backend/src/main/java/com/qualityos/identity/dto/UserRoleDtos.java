package com.qualityos.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class UserRoleDtos {

    private UserRoleDtos() {
    }

    public record InviteRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotEmpty Set<@NotNull UUID> roleIds) {

        @Override
        public String toString() {
            return "InviteRequest[" + email + "]";
        }
    }

    public record InvitationResponse(UUID id, String email, Instant expiresAt, boolean expired) {
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
            List<RoleSummary> roles,
            Set<UUID> facilityIds) {
    }

    /** @param facilityIds empty means every facility */
    public record UpdateMemberFacilitiesRequest(@NotNull Set<UUID> facilityIds) {
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
