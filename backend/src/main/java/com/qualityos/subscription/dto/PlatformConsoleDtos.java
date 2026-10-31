package com.qualityos.subscription.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class PlatformConsoleDtos {

    private PlatformConsoleDtos() {
    }

    /** Only what a signed-in member needs. The rest of the settings stay with operators. */
    public record CustomerNotices(boolean maintenance, String supportEmail, List<CustomerAnnouncement> announcements) {
    }

    public record CustomerAnnouncement(UUID id, String title, String message, String severity) {
    }

    public record PlatformSettings(
            int defaultTrialDays, int defaultGraceDays, boolean maintenanceMode, boolean registrationOpen,
            String supportEmail, long version, Instant updatedAt) {
    }

    public record SaveSettingsRequest(
            @Min(0) @Max(365) int defaultTrialDays,
            @Min(0) @Max(90) int defaultGraceDays,
            boolean maintenanceMode,
            boolean registrationOpen,
            @Email @Size(max = 320) String supportEmail,
            long version,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record Announcement(
            UUID id, String title, String message, String severity, String audience, LocalDate startsOn,
            LocalDate endsOn, boolean active, long version) {
    }

    public record SaveAnnouncementRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 2000) String message,
            @NotNull @Pattern(regexp = "INFO|WARNING|CRITICAL") String severity,
            @NotNull @Pattern(regexp = "ALL|TRIAL|PAID") String audience,
            @NotNull LocalDate startsOn,
            @NotNull LocalDate endsOn,
            boolean active,
            long version,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record OpenTicketRequest(
            @NotNull UUID tenantId,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 4000) String note) {
    }

    public record TicketStatusRequest(
            @NotNull @Pattern(regexp = "OPEN|IN_PROGRESS|WAITING_FOR_CUSTOMER|RESOLVED|CLOSED") String status,
            long version) {
    }

    public record AddNoteRequest(@NotBlank @Size(max = 4000) String body) {
    }

    public record Ticket(
            UUID id, UUID tenantId, String organisation, String subject, String status, String openedBy,
            Instant createdAt, Instant updatedAt, long version) {
    }

    public record TicketList(int total, List<Ticket> items) {
    }

    public record TicketNote(UUID id, String author, String body, Instant createdAt) {
    }

    public record SupportError(Instant occurredAt, String template, String errorType) {
    }
}
