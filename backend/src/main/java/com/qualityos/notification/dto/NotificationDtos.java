package com.qualityos.notification.dto;

import com.qualityos.notification.service.NotificationType;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    public record NotificationResponse(
            UUID id, NotificationType type, String title, String body, String link, Instant createdAt, boolean read) {
    }

    public record UnreadCount(long count) {
    }

    public record PreferenceResponse(NotificationType type, boolean emailEnabled) {
    }

    public record UpdatePreferenceRequest(@NotNull NotificationType type, @NotNull Boolean emailEnabled) {
    }
}
