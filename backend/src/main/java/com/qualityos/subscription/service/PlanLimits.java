package com.qualityos.subscription.service;

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

    private final JdbcTemplate jdbc;

    PlanLimits(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireUserPlace() {
        Limits limits = current();
        Long used = jdbc.queryForObject("SELECT " + MEMBERS, Long.class);
        if (limits.users() != null && used >= limits.users()) {
            throw new BusinessRuleException("The " + limits.plan() + " plan allows " + limits.users()
                    + " users, counting pending invitations. Change plan to add more.");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireFacilityPlace() {
        Limits limits = current();
        Long used = jdbc.queryForObject("SELECT " + FACILITIES, Long.class);
        if (limits.facilities() != null && used >= limits.facilities()) {
            throw new BusinessRuleException("The " + limits.plan() + " plan allows " + limits.facilities()
                    + " facilities. Change plan to add more.");
        }
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
