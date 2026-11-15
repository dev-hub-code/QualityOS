package com.qualityos.auditmanagement.service;

import com.qualityos.common.job.JobRunner;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs hourly so that a reminder goes out soon after each plant's day starts, wherever that plant is. */
@Component
@ConditionalOnProperty(name = "qualityos.jobs.enabled", havingValue = "true")
class AuditReminderJob {

    private static final Duration LEASE = Duration.ofMinutes(30);

    private final QualityAuditService audits;
    private final JobRunner jobs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration interval;

    AuditReminderJob(
            QualityAuditService audits,
            JobRunner jobs,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${qualityos.jobs.audit-reminders.interval}") Duration interval) {
        this.audits = audits;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.interval = interval;
    }

    @Scheduled(
            initialDelayString = "${qualityos.jobs.audit-reminders.initial-delay}",
            fixedDelayString = "${qualityos.jobs.audit-reminders.interval}")
    void remind() {
        jobs.run("audit-reminders", LEASE, interval, context -> {
            List<UUID> tenants =
                    tx.execute(status -> jdbc.queryForList("SELECT tenants_with_audits_due()", UUID.class));
            jobs.forEachTenant(context, tenants, tenant -> {
                int sent = audits.remindUpcoming();
                context.processed(sent);
                context.notified(sent);
            });
        });
    }
}
