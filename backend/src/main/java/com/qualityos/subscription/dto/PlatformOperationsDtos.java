package com.qualityos.subscription.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PlatformOperationsDtos {

    private PlatformOperationsDtos() {
    }

    /** The field is {@code password}, so request logging and audit already treat it as secret. */
    public record ReauthenticateRequest(@NotBlank @Size(max = 200) String password) {
    }

    public record AddOperatorRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank String role,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record ChangeOperatorRequest(
            @NotBlank String role, @NotNull Boolean active, @NotBlank @Size(max = 500) String reason) {
    }

    public record RetryEmailRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record Operator(
            UUID userId, String email, String fullName, String role, Instant addedAt, Instant deactivatedAt,
            String addedBy, Instant lastActivity, String secondFactor) {
    }

    public record ResetSecondFactorRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** NONE, PENDING or ENROLLED. */
    public record SecondFactorState(String state) {
    }

    /** Shown once. The secret is what an authenticator app needs, by scanning the URI or typing it. */
    public record SecondFactorEnrolment(String secret, String otpauthUri) {

        @Override
        public String toString() {
            return "SecondFactorEnrolment[]";
        }
    }

    public record ConfirmSecondFactorRequest(
            @NotBlank @Pattern(regexp = "[0-9]{6}", message = "Enter the six-digit code.") String code) {

        @Override
        public String toString() {
            return "ConfirmSecondFactorRequest[]";
        }
    }

    public record AuditFilter(String q, String action, String entityType, LocalDate from, LocalDate to) {
    }

    public record AuditEntry(
            UUID id, Instant occurredAt, String actor, String action, String entityType, String entityId,
            String organisation, Map<String, Object> before, Map<String, Object> after, String reason,
            String correlationId, String ipAddress) {
    }

    public record AuditList(int total, List<AuditEntry> items) {
    }

    public record EmailEntry(
            UUID id, UUID tenantId, String organisation, UUID notificationId, String recipient, String template,
            String status, long attempts, Instant lastAttempt, String errorType, boolean retryable) {
    }

    public record EmailList(int total, List<EmailEntry> items) {
    }

    public record NotificationEntry(
            UUID id, Instant sentAt, String type, String title, String organisation, UUID tenantId, String recipient,
            String emailStatus) {
    }

    public record NotificationList(int total, List<NotificationEntry> items) {
    }
}
