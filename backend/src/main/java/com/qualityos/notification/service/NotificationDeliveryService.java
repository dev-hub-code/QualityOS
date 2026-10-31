package com.qualityos.notification.service;

import com.qualityos.common.job.JobContext;
import com.qualityos.common.job.JobRunner;
import com.qualityos.common.mail.EmailRequest;
import com.qualityos.common.mail.EmailService;
import com.qualityos.common.mail.EmailTemplate;
import com.qualityos.common.mail.MailGateway.MailDeliveryException;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.identity.service.TenantAccessService.MemberContact;
import com.qualityos.notification.entity.Notification;
import com.qualityos.organization.dto.OrganizationResponse;
import com.qualityos.organization.service.OrganizationService;
import com.qualityos.notification.repository.NotificationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final EmailService emails;
    private final OrganizationService organizations;
    private final JobRunner jobs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String appUrl;

    NotificationDeliveryService(
            NotificationRepository notifications,
            TenantAccessService access,
            EmailService emails,
            OrganizationService organizations,
            JobRunner jobs,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${qualityos.app-url}") String appUrl) {
        this.notifications = notifications;
        this.access = access;
        this.emails = emails;
        this.organizations = organizations;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.appUrl = appUrl;
    }

    public void deliverPending(JobContext context) {
        List<UUID> tenants = tx.execute(status ->
                jdbc.queryForList("SELECT tenants_with_pending_email()", UUID.class));
        // One tenant's problem must not hold up everyone else's mail.
        jobs.forEachTenant(context, tenants, tenant -> deliverForCurrentTenant(context));
    }

    private void deliverForCurrentTenant(JobContext context) {
        List<UUID> due = tx.execute(status ->
                notifications.findDueForEmail(Instant.now(), PageRequest.of(0, BATCH_SIZE)));
        due.forEach(id -> tx.executeWithoutResult(status -> notifications.claimForEmail(id).ifPresent(this::deliver)));
        context.processed(due.size());
    }

    private void deliver(Notification notification) {
        // Checked at send time: someone may have left the organisation since this was created.
        Optional<MemberContact> contact = access.activeMemberContact(notification.getUserId());
        if (contact.isEmpty()) {
            notification.emailSkipped();
            return;
        }
        try {
            emails.send(request(notification, contact.get()));
            notification.emailSent();
        } catch (MailDeliveryException ex) {
            log.warn("Notification email {} failed, attempt {}", notification.getId(),
                    notification.getEmailAttempts() + 1, ex);
            notification.emailFailed(Instant.now());
        }
    }

    private EmailRequest request(Notification notification, MemberContact contact) {
        OrganizationResponse organization = organizations.getCurrent();
        Map<String, Object> variables = new HashMap<>();
        EmailTemplate template = EmailTemplate.GENERAL;
        if (notification.getEmailTemplate() != null) {
            template = EmailTemplate.valueOf(notification.getEmailTemplate());
            variables.putAll(notification.getEmailVariables());
            variables.computeIfPresent("dueDate", (name, date) -> LocalDate.parse((String) date));
        } else {
            variables.put("title", notification.getTitle());
            variables.put("body", notification.getBody());
        }
        variables.put("recipientName", contact.fullName());
        variables.put("organizationName", organization.name());
        variables.put("actionUrl", notification.getLink() == null ? appUrl : appUrl + notification.getLink());
        return new EmailRequest(notification.getTenantId(), contact.email(), template,
                emails.language(contact.language(), organization.locale()), variables, notification.getId());
    }
}
