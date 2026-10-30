package com.qualityos.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class AuthDtos {

    private static final int MIN_PASSWORD = 10;
    private static final int MAX_PASSWORD = 128;

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = MIN_PASSWORD, max = MAX_PASSWORD) String password,
            @NotBlank @Size(max = 200) String organizationName) {

        @Override
        public String toString() {
            return "RegisterRequest[" + email + "]";
        }
    }

    /** @param tenantId optional preferred organisation; honoured only if the user is a member */
    public record LoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = MAX_PASSWORD) String password,
            UUID tenantId) {

        @Override
        public String toString() {
            return "LoginRequest[" + email + "]";
        }
    }

    public record RefreshRequest(@NotBlank @Size(max = 128) String refreshToken) {

        @Override
        public String toString() {
            return "RefreshRequest[***]";
        }
    }

    public record SwitchTenantRequest(@NotNull UUID tenantId, @NotBlank @Size(max = 128) String refreshToken) {

        @Override
        public String toString() {
            return "SwitchTenantRequest[" + tenantId + "]";
        }
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = MAX_PASSWORD) String currentPassword,
            @NotBlank @Size(min = MIN_PASSWORD, max = MAX_PASSWORD) String newPassword) {

        @Override
        public String toString() {
            return "ChangePasswordRequest[***]";
        }
    }

    public record CreateOrganizationRequest(@NotBlank @Size(max = 200) String name) {
    }

    public record TokenResponse(String accessToken, String refreshToken, long expiresInSeconds, UUID tenantId) {

        @Override
        public String toString() {
            return "TokenResponse[" + tenantId + "]";
        }
    }

    public record UserSummary(UUID id, String email, String fullName) {
    }

    public record OrganizationSummary(UUID id, String name) {
    }

    public record MeResponse(
            UserSummary user,
            UUID tenantId,
            boolean onboarded,
            Set<String> permissions,
            List<OrganizationSummary> organizations) {
    }
}
