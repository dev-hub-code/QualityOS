package com.qualityos.traceability.service;

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

/** Nobody is told when goods leave without a confirmation, so this asks about dispatches that have sat too long. */
@Component
@ConditionalOnProperty(name = "qualityos.jobs.enabled", havingValue = "true")
class UnconfirmedShipmentJob {

    private static final Duration LEASE = Duration.ofMinutes(30);

    private final DispatchService dispatches;
    private final JobRunner jobs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration interval;

    UnconfirmedShipmentJob(
            DispatchService dispatches,
            JobRunner jobs,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${qualityos.jobs.unconfirmed-shipments.interval}") Duration interval) {
        this.dispatches = dispatches;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.interval = interval;
    }

    @Scheduled(
            initialDelayString = "${qualityos.jobs.unconfirmed-shipments.initial-delay}",
            fixedDelayString = "${qualityos.jobs.unconfirmed-shipments.interval}")
    void remind() {
        jobs.run("unconfirmed-shipments", LEASE, interval, context -> {
            List<UUID> tenants = tx.execute(status ->
                    jdbc.queryForList("SELECT tenants_with_unconfirmed_dispatches()", UUID.class));
            jobs.forEachTenant(context, tenants, tenant -> {
                int waiting = dispatches.remindUnconfirmed();
                context.processed(waiting);
                context.notified(waiting > 0 ? 1 : 0);
            });
        });
    }
}
