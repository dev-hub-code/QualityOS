package com.qualityos.subscription.service;

import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.notification.service.NotificationService;
import com.qualityos.notification.service.NotificationType;
import com.qualityos.common.attachment.AttachmentQuota;
import com.qualityos.common.exception.BusinessRuleException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Takes the subscription row's lock, so that two requests at once cannot both take the last place. */
@Component
public class PlanLimits implements AttachmentQuota {

    static final String MEMBERS = """
            (SELECT count(*) FROM tenant_memberships WHERE status = 'ACTIVE')
            + (SELECT count(*) FROM invitations WHERE status = 'PENDING' AND expires_at > now())
            """;
    static final String FACILITIES = "(SELECT count(*) FROM facilities WHERE archived_at IS NULL)";

    static final String STORAGE = "(SELECT COALESCE(sum(size_bytes), 0) FROM attachments WHERE deleted_at IS NULL)";
    static final String ATTACHMENTS = "(SELECT count(*) FROM attachments WHERE deleted_at IS NULL)";
    static final String INSPECTIONS = """
            (SELECT count(*) FROM inspections i JOIN tenants t ON t.id = i.tenant_id
             WHERE i.created_at >= date_trunc('month', now() AT TIME ZONE t.timezone) AT TIME ZONE t.timezone)
            """;

    private static final long MEGABYTE = 1_048_576L;
    private static final String LIMITS = """
            SELECT p.max_users, p.max_facilities, p.max_storage_mb, p.max_monthly_inspections, p.max_attachments, p.name
            FROM subscriptions s JOIN subscription_plans p ON p.id = s.plan_id
            FOR UPDATE OF s
            """;

    private record Limits(
            Integer users, Integer facilities, Integer storageMb, Integer inspections, Integer attachments, String plan) {
    }

    private static final double NEAR = 0.8;
    private static final String LINK = "/settings/billing";

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final TenantAccessService access;

    PlanLimits(JdbcTemplate jdbc, NotificationService notifications, TenantAccessService access) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.access = access;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireUserPlace() {
        Limits limits = current();
        Long used = jdbc.queryForObject("SELECT " + MEMBERS, Long.class);
        if (limits.users() != null && used >= limits.users()) {
            throw new BusinessRuleException("The " + limits.plan() + " plan allows " + limits.users()
                    + " users, counting pending invitations. Change plan to add more.");
        }
        warn(limits, "users, counting pending invitations", "user", used, used + 1, limits.users());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireFacilityPlace() {
        Limits limits = current();
        Long used = jdbc.queryForObject("SELECT " + FACILITIES, Long.class);
        if (limits.facilities() != null && used >= limits.facilities()) {
            throw new BusinessRuleException("The " + limits.plan() + " plan allows " + limits.facilities()
                    + " facilities. Change plan to add more.");
        }
        warn(limits, "facilities", "facility", used, used + 1, limits.facilities());
    }

    /** Runs when an inspection is started. Records already made stay, and a new month starts the count again. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireInspectionPlace() {
        Limits limits = current();
        Long used = jdbc.queryForObject("SELECT " + INSPECTIONS, Long.class);
        if (limits.inspections() != null && used >= limits.inspections()) {
            throw new BusinessRuleException("The " + limits.plan() + " plan allows " + limits.inspections()
                    + " inspections a month. Change plan to start more.");
        }
        warn(limits, "inspections this month", "monthly inspection", used, used + 1, limits.inspections());
    }

    /** Before the file is stored, so that a refused upload leaves nothing behind. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAttachmentPlace(long bytes) {
        Limits limits = current();
        Long count = jdbc.queryForObject("SELECT " + ATTACHMENTS, Long.class);
        if (limits.attachments() != null && count >= limits.attachments()) {
            throw new BusinessRuleException("The " + limits.plan() + " plan allows " + limits.attachments()
                    + " attachments. Change plan to add more.");
        }
        Long stored = jdbc.queryForObject("SELECT " + STORAGE, Long.class);
        if (limits.storageMb() != null && stored + bytes > limits.storageMb() * MEGABYTE) {
            throw new BusinessRuleException("This file would take the organisation past the " + limits.plan()
                    + " plan's " + limits.storageMb() + " MB of storage. Change plan to add more.");
        }
        warn(limits, "attachments", "attachment", count, count + 1, limits.attachments());
        if (limits.storageMb() != null) {
            long mb = limits.storageMb() * MEGABYTE;
            if (crossed(stored, stored + bytes, mb) != null) {
                warn(limits, "MB of storage", "storage", stored / MEGABYTE, (stored + bytes + MEGABYTE - 1) / MEGABYTE,
                        limits.storageMb(), crossed(stored, stored + bytes, mb));
            }
        }
    }

    /**
     * Tells those who manage the subscription when this action takes the organisation past 80% of a limit, or onto
     * it. Each threshold is crossed once, so each warns once until usage falls back below it.
     */
    private void warn(Limits limits, String counted, String noun, long before, long after, Integer limit) {
        if (limit != null) {
            warn(limits, counted, noun, before, after, limit, crossed(before, after, limit));
        }
    }

    private void warn(Limits limits, String counted, String noun, long before, long after, long limit, String level) {
        if (level == null) {
            return;
        }
        boolean reached = "REACHED".equals(level);
        String title = reached ? "The " + limits.plan() + " plan's " + noun + " limit is reached"
                : "Near the " + limits.plan() + " plan's " + noun + " limit";
        String body = after + " of " + limit + " " + counted + ". "
                + (reached ? "Change plan to add more." : "Change plan before it stops your work.");
        notifications.notifyIncludingActor(access.holdersOf(PlanAccess.SUBSCRIPTION_MANAGE),
                NotificationType.PLAN_LIMIT_WARNING, title, body, LINK);
    }

    /** @return REACHED, NEAR, or null when this step crosses neither threshold */
    static String crossed(long before, long after, long limit) {
        if (before < limit && after >= limit) {
            return "REACHED";
        }
        long near = (long) Math.ceil(limit * NEAR);
        return before < near && after >= near ? "NEAR" : null;
    }

    private Limits current() {
        return jdbc.query(LIMITS, rows -> {
            if (!rows.next()) {
                throw new BusinessRuleException("This organisation has no subscription.");
            }
            return new Limits(rows.getObject("max_users", Integer.class),
                    rows.getObject("max_facilities", Integer.class), rows.getObject("max_storage_mb", Integer.class),
                    rows.getObject("max_monthly_inspections", Integer.class),
                    rows.getObject("max_attachments", Integer.class), rows.getString("name"));
        });
    }
}
