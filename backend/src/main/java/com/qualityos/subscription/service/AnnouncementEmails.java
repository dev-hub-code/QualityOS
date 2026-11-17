package com.qualityos.subscription.service;

import com.qualityos.common.mail.EmailContent;
import com.qualityos.common.mail.EmailTemplate;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.notification.service.NotificationService;
import com.qualityos.notification.service.NotificationType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emails an announcement to every active member of an organisation it reaches. The banner shows it to people who are
 * signed in; the email reaches the rest. Each organisation gets each announcement once.
 */
@Service
public class AnnouncementEmails {

    private final JdbcTemplate jdbc;
    private final TenantAccessService access;
    private final NotificationService notifications;

    AnnouncementEmails(JdbcTemplate jdbc, TenantAccessService access, NotificationService notifications) {
        this.jdbc = jdbc;
        this.access = access;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<UUID> tenantsAwaiting() {
        return jdbc.queryForList("SELECT tenants_awaiting_announcement_email()", UUID.class);
    }

    /**
     * Sends the current organisation's due announcements. Marking and sending share a transaction, so one that fails
     * goes again on the next run.
     *
     * @return how many people were told
     */
    @Transactional
    public int sendDue() {
        record Due(String title, String message) {
        }
        List<Due> due = jdbc.query("SELECT title, message FROM claim_announcement_emails()",
                (rows, row) -> new Due(rows.getString("title"), rows.getString("message")));
        if (due.isEmpty()) {
            return 0;
        }
        Set<UUID> members = access.activeMemberIds();
        for (Due announcement : due) {
            notifications.notifyIncludingActor(members, NotificationType.ANNOUNCEMENT, announcement.title(),
                    announcement.message(), "/dashboard", new EmailContent(EmailTemplate.ANNOUNCEMENT,
                            Map.of("title", announcement.title(), "message", announcement.message())));
        }
        return due.size() * members.size();
    }
}
