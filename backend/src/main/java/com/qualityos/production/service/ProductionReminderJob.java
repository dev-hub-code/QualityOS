package com.qualityos.production.service;

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

/** Runs hourly so that each plant's day is noticed soon after it ends, wherever that plant is. */
@Component
@ConditionalOnProperty(name = "qualityos.jobs.enabled", havingValue = "true")
class ProductionReminderJob {

    private static final Duration LEASE = Duration.ofMinutes(30);

    private final ProductionReminders reminders;
    private final JobRunner jobs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration interval;

    ProductionReminderJob(
            ProductionReminders reminders,
            JobRunner jobs,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${qualityos.jobs.production-reminders.interval}") Duration interval) {
        this.reminders = reminders;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.interval = interval;
    }

    @Scheduled(
            initialDelayString = "${qualityos.jobs.production-reminders.initial-delay}",
            fixedDelayString = "${qualityos.jobs.production-reminders.interval}")
    void remind() {
        jobs.run("production-reminders", LEASE, interval, context -> {
            List<UUID> tenants =
                    tx.execute(status -> jdbc.queryForList("SELECT tenants_with_production_due()", UUID.class));
            jobs.forEachTenant(context, tenants, tenant -> {
                int sent = reminders.remindOverdue();
                context.processed(sent);
                context.notified(sent);
            });
        });
    }
}
