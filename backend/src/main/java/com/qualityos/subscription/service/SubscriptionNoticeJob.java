package com.qualityos.subscription.service;

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

/** Only sends notices. Access follows the dates, whether or not this has run. */
@Component
@ConditionalOnProperty(name = "qualityos.jobs.enabled", havingValue = "true")
class SubscriptionNoticeJob {

    private static final Duration LEASE = Duration.ofMinutes(30);

    private final SubscriptionService subscriptions;
    private final JobRunner jobs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration interval;

    SubscriptionNoticeJob(
            SubscriptionService subscriptions,
            JobRunner jobs,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${qualityos.jobs.subscription-notices.interval}") Duration interval) {
        this.subscriptions = subscriptions;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.interval = interval;
    }

    @Scheduled(
            initialDelayString = "${qualityos.jobs.subscription-notices.initial-delay}",
            fixedDelayString = "${qualityos.jobs.subscription-notices.interval}")
    void send() {
        jobs.run("subscription-notices", LEASE, interval, context -> {
            List<UUID> tenants =
                    tx.execute(status -> jdbc.queryForList("SELECT tenants_with_subscriptions_ending()", UUID.class));
            jobs.forEachTenant(context, tenants, tenant -> {
                int sent = subscriptions.sendExpiryNotice();
                context.processed(sent);
                context.notified(sent);
            });
        });
    }
}
