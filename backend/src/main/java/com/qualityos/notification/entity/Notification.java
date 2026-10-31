package com.qualityos.notification.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import com.qualityos.notification.service.NotificationType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "notifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends TenantOwnedEntity {

    public enum EmailStatus { NONE, PENDING, SENT, SKIPPED, FAILED }

    /** With doubling delays, this keeps trying for about an hour. */
    static final int MAX_EMAIL_ATTEMPTS = 6;
    private static final Duration FIRST_RETRY = Duration.ofMinutes(1);

    private UUID userId;

    @Enumerated(EnumType.STRING)
    private NotificationType type;

    private String title;
    private String body;
    private String link;
    private Instant readAt;

    @Enumerated(EnumType.STRING)
    private EmailStatus emailStatus;

    private int emailAttempts;
    private Instant emailNextAttemptAt;

    public Notification(UUID userId, NotificationType type, String title, String body, String link, boolean email) {
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.link = link;
        this.emailStatus = email ? EmailStatus.PENDING : EmailStatus.NONE;
    }

    public void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }

    public void emailSent() {
        emailStatus = EmailStatus.SENT;
    }

    public void emailSkipped() {
        emailStatus = EmailStatus.SKIPPED;
    }

    public void emailFailed(Instant now) {
        emailAttempts++;
        if (emailAttempts >= MAX_EMAIL_ATTEMPTS) {
            emailStatus = EmailStatus.FAILED;
        } else {
            emailNextAttemptAt = now.plus(FIRST_RETRY.multipliedBy(1L << (emailAttempts - 1)));
        }
    }
}
