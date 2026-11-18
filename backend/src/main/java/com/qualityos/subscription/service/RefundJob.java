package com.qualityos.subscription.service;

import com.qualityos.common.job.JobRunner;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finishes gateway refunds left waiting: one whose answer never came back, or whose webhook never arrived. Each is
 * looked up at Cashfree, and sent again under its own reference only if Cashfree never received it.
 */
@lombok.extern.slf4j.Slf4j
@Component
@ConditionalOnProperty(name = "qualityos.jobs.enabled", havingValue = "true")
class RefundJob {

    private static final Duration LEASE = Duration.ofMinutes(30);
    /** Long enough that a refund still being sent is never sent a second time alongside it. */
    static final Duration SETTLE = Duration.ofMinutes(2);

    private final BillingRefunds refunds;
    private final JobRunner jobs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration interval;

    RefundJob(
            BillingRefunds refunds,
            JobRunner jobs,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${qualityos.jobs.refunds.interval}") Duration interval) {
        this.refunds = refunds;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.interval = interval;
    }

    @Scheduled(
            initialDelayString = "${qualityos.jobs.refunds.initial-delay}",
            fixedDelayString = "${qualityos.jobs.refunds.interval}")
    void reconcile() {
        jobs.run("refunds", LEASE, interval, context -> {
            List<UUID> tenants = tx.execute(status ->
                    jdbc.queryForList("SELECT tenants_with_pending_refunds()", UUID.class));
            jobs.forEachTenant(context, tenants, tenant -> {
                for (UUID refund : refunds.waiting(Instant.now().minus(SETTLE))) {
                    // One Cashfree cannot answer for now is tried again next time; the others go on.
                    try {
                        refunds.reconcile(refund);
                        context.processed(1);
                    } catch (RuntimeException e) {
                        context.failed();
                        log.warn("Refund {} could not be checked yet: {}", refund, e.getMessage());
                    }
                }
            });
        });
    }
}
