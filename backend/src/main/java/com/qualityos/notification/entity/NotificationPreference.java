package com.qualityos.notification.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import com.qualityos.notification.service.NotificationType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A row exists only once a user has changed a type from its default of emailing them. */
@Getter
@Entity
@Table(name = "notification_preferences")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationPreference extends TenantOwnedEntity {

    private UUID userId;

    @Enumerated(EnumType.STRING)
    private NotificationType type;

    @Setter
    private boolean emailEnabled;

    public NotificationPreference(UUID userId, NotificationType type, boolean emailEnabled) {
        this.userId = userId;
        this.type = type;
        this.emailEnabled = emailEnabled;
    }
}
