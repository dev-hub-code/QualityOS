package com.qualityos.notification.service;

import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.response.PageResponse;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.notification.dto.NotificationDtos.NotificationResponse;
import com.qualityos.notification.dto.NotificationDtos.PreferenceResponse;
import com.qualityos.notification.dto.NotificationDtos.UnreadCount;
import com.qualityos.notification.entity.Notification;
import com.qualityos.notification.entity.NotificationPreference;
import com.qualityos.notification.repository.NotificationPreferenceRepository;
import com.qualityos.notification.repository.NotificationRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Every inbox query is scoped to the signed-in user: permissions do not open another person's notifications. */
@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final NotificationPreferenceRepository preferences;

    NotificationService(NotificationRepository notifications, NotificationPreferenceRepository preferences) {
        this.notifications = notifications;
        this.preferences = preferences;
    }

    /**
     * Joins the caller's transaction, so a notification exists only if the event it announces was committed.
     * Emails go out afterwards from {@link NotificationDeliveryService}. The person acting is never told about
     * their own action.
     *
     * @param link a path within the application
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notify(Set<UUID> recipients, NotificationType type, String title, String body, String link) {
        Set<UUID> targets = new HashSet<>(recipients);
        targets.remove(null);
        TenantContext.findCurrentUserId().ifPresent(targets::remove);
        if (targets.isEmpty()) {
            return;
        }
        Set<UUID> optedOut = preferences.findByTypeAndUserIdInAndEmailEnabledFalse(type, targets).stream()
                .map(NotificationPreference::getUserId)
                .collect(Collectors.toSet());
        notifications.saveAll(targets.stream()
                .map(userId -> new Notification(userId, type, title, body, link, !optedOut.contains(userId)))
                .toList());
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> inbox(boolean unreadOnly, Pageable pageable) {
        UUID userId = TenantContext.getCurrentUserId();
        Page<Notification> page = unreadOnly
                ? notifications.findByUserIdAndReadAtIsNull(userId, pageable)
                : notifications.findByUserId(userId, pageable);
        return PageResponse.from(page, NotificationService::toResponse);
    }

    @Transactional(readOnly = true)
    public UnreadCount unreadCount() {
        return new UnreadCount(notifications.countByUserIdAndReadAtIsNull(TenantContext.getCurrentUserId()));
    }

    @Transactional
    public void markRead(UUID id) {
        notifications.findByIdAndUserId(id, TenantContext.getCurrentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Notification", id))
                .markRead(Instant.now());
    }

    @Transactional
    public void markAllRead() {
        notifications.markAllRead(TenantContext.getCurrentUserId(), Instant.now());
    }

    @Transactional(readOnly = true)
    public List<PreferenceResponse> preferences() {
        Map<NotificationType, Boolean> saved = preferences.findByUserId(TenantContext.getCurrentUserId()).stream()
                .collect(Collectors.toMap(NotificationPreference::getType, NotificationPreference::isEmailEnabled));
        return Arrays.stream(NotificationType.values())
                .map(type -> new PreferenceResponse(type, saved.getOrDefault(type, true)))
                .toList();
    }

    @Transactional
    public void updatePreference(NotificationType type, boolean emailEnabled) {
        UUID userId = TenantContext.getCurrentUserId();
        preferences.findByUserIdAndType(userId, type).ifPresentOrElse(
                preference -> preference.setEmailEnabled(emailEnabled),
                () -> preferences.save(new NotificationPreference(userId, type, emailEnabled)));
    }

    private static NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getTitle(),
                notification.getBody(),
                notification.getLink(),
                notification.getCreatedAt(),
                notification.getReadAt() != null);
    }
}
