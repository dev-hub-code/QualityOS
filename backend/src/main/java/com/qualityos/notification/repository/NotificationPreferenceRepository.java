package com.qualityos.notification.repository;

import com.qualityos.notification.entity.NotificationPreference;
import com.qualityos.notification.service.NotificationType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {

    List<NotificationPreference> findByUserId(UUID userId);

    Optional<NotificationPreference> findByUserIdAndType(UUID userId, NotificationType type);

    List<NotificationPreference> findByTypeAndUserIdInAndEmailEnabledFalse(NotificationType type, Collection<UUID> userIds);
}
