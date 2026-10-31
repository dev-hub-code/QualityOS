package com.qualityos.notification.repository;

import com.qualityos.notification.entity.Notification;
import com.qualityos.notification.service.NotificationType;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    boolean existsByTypeAndCreatedAtAfter(NotificationType type, Instant after);

    Page<Notification> findByUserId(UUID userId, Pageable pageable);

    Page<Notification> findByUserIdAndReadAtIsNull(UUID userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(UUID userId);

    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
    int markAllRead(UUID userId, Instant now);

    @Query("""
            select n.id from Notification n
            where n.emailStatus = com.qualityos.notification.entity.Notification.EmailStatus.PENDING
              and (n.emailNextAttemptAt is null or n.emailNextAttemptAt <= :now)
            order by n.createdAt
            """)
    List<UUID> findDueForEmail(Instant now, Pageable pageable);

    /** Skips a row another instance is sending, so that running two instances does not send twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select n from Notification n
            where n.id = :id and n.emailStatus = com.qualityos.notification.entity.Notification.EmailStatus.PENDING
            """)
    Optional<Notification> claimForEmail(UUID id);
}
