package com.qualityos.notification.service;

import com.qualityos.common.mail.MailGateway;
import com.qualityos.common.mail.MailGateway.MailDeliveryException;
import com.qualityos.common.mail.MailMessage;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.notification.entity.Notification;
import com.qualityos.notification.repository.NotificationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends notification emails. Each tenant's rows are read under that tenant's own context, so row-level security
 * still applies. The only thing seen across tenants is which tenant ids have work waiting.
 *
 * <p>Delivery is at least once: a crash between sending and committing sends that email again.
 */
@Service
public class NotificationDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);
    private static final int BATCH_SIZE = 100;

    private final NotificationRepository notifications;
    private final TenantAccessService access;
    private final MailGateway mail;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String appUrl;

    NotificationDeliveryService(
            NotificationRepository notifications,
            TenantAccessService access,
            MailGateway mail,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${qualityos.app-url}") String appUrl) {
        this.notifications = notifications;
        this.access = access;
        this.mail = mail;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.appUrl = appUrl;
    }

    public void deliverPending() {
        List<UUID> tenants = tx.execute(status ->
                jdbc.queryForList("SELECT tenants_with_pending_email()", UUID.class));
        for (UUID tenantId : tenants) {
            try {
                TenantContext.runAs(tenantId, null, this::deliverForCurrentTenant);
            } catch (RuntimeException ex) {
                // One tenant's problem must not hold up everyone else's mail.
                log.error("Notification delivery failed for tenant {}", tenantId, ex);
            }
        }
    }

    private void deliverForCurrentTenant() {
        List<UUID> due = tx.execute(status ->
                notifications.findDueForEmail(Instant.now(), PageRequest.of(0, BATCH_SIZE)));
        due.forEach(id -> tx.executeWithoutResult(status -> notifications.claimForEmail(id).ifPresent(this::deliver)));
    }

    private void deliver(Notification notification) {
        // Checked at send time: someone may have left the organisation since this was created.
        Optional<String> address = access.activeMemberEmail(notification.getUserId());
        if (address.isEmpty()) {
            notification.emailSkipped();
            return;
        }
        try {
            mail.send(new MailMessage(address.get(), notification.getTitle(), text(notification)));
            notification.emailSent();
        } catch (MailDeliveryException ex) {
            log.warn("Notification email {} failed, attempt {}", notification.getId(),
                    notification.getEmailAttempts() + 1, ex);
            notification.emailFailed(Instant.now());
        }
    }

    private String text(Notification notification) {
        return notification.getLink() == null
                ? notification.getBody()
                : notification.getBody() + "\n\n" + appUrl + notification.getLink();
    }
}
