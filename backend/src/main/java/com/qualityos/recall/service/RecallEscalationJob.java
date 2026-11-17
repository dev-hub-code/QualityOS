package com.qualityos.recall.service;

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

/** Runs hourly, so that a customer's silence is noticed soon after their time to answer ends at each plant. */
@Component
@ConditionalOnProperty(name = "qualityos.jobs.enabled", havingValue = "true")
class RecallEscalationJob {

    private static final Duration LEASE = Duration.ofMinutes(30);

    private final RecallService recalls;
    private final JobRunner jobs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration interval;

    RecallEscalationJob(
            RecallService recalls,
            JobRunner jobs,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${qualityos.jobs.recall-escalations.interval}") Duration interval) {
        this.recalls = recalls;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.interval = interval;
    }

    @Scheduled(
            initialDelayString = "${qualityos.jobs.recall-escalations.initial-delay}",
            fixedDelayString = "${qualityos.jobs.recall-escalations.interval}")
    void escalate() {
        jobs.run("recall-escalations", LEASE, interval, context -> {
            List<UUID> tenants =
                    tx.execute(status -> jdbc.queryForList("SELECT tenants_with_unanswered_recalls()", UUID.class));
            jobs.forEachTenant(context, tenants, tenant -> {
                int sent = recalls.escalateUnanswered();
                context.processed(sent);
                context.notified(sent);
            });
        });
    }
}
