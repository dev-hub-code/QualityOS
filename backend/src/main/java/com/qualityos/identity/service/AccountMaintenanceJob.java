package com.qualityos.identity.service;

import com.qualityos.common.job.JobRunner;
import com.qualityos.identity.repository.AccountTokenRepository;
import com.qualityos.identity.repository.RefreshTokenRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Tokens belong to users, not tenants, so this job holds no tenant context and touches no tenant's data. */
@Component
@ConditionalOnProperty(name = "qualityos.jobs.enabled", havingValue = "true")
class AccountMaintenanceJob {

    /** Kept a while after expiry so that a late reuse of a refresh token is still recognised as reuse. */
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final Duration EXECUTION_RETENTION = Duration.ofDays(30);
    private static final Duration LEASE = Duration.ofHours(1);

    private final RefreshTokenRepository refreshTokens;
    private final AccountTokenRepository accountTokens;
    private final JobRunner jobs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration interval;
    private final Duration unverifiedRetention;
    private final boolean unverifiedMayWork;

    AccountMaintenanceJob(
            RefreshTokenRepository refreshTokens,
            AccountTokenRepository accountTokens,
            JobRunner jobs,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${qualityos.jobs.account-maintenance.interval}") Duration interval,
            @Value("${app.registration.unverified-retention}") Duration unverifiedRetention,
            @Value("${app.registration.allow-unverified-login}") boolean unverifiedMayWork) {
        this.refreshTokens = refreshTokens;
        this.accountTokens = accountTokens;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.interval = interval;
        this.unverifiedRetention = unverifiedRetention;
        this.unverifiedMayWork = unverifiedMayWork;
    }

    @Scheduled(
            initialDelayString = "${qualityos.jobs.account-maintenance.initial-delay}",
            fixedDelayString = "${qualityos.jobs.account-maintenance.interval}")
    void purge() {
        jobs.run("account-maintenance", LEASE, interval, context -> {
            Instant cutoff = Instant.now().minus(RETENTION);
            context.processed(tx.execute(status ->
                    refreshTokens.deleteExpiredBefore(cutoff) + accountTokens.deleteExpiredBefore(cutoff)));
            context.processed(tx.execute(status -> jdbc.update(
                    "DELETE FROM scheduled_job_executions WHERE started_at < ?",
                    Timestamp.from(Instant.now().minus(EXECUTION_RETENTION)))));
            // With unverified login allowed, a pending account may be in real use, so it is left alone.
            if (!unverifiedMayWork) {
                context.processed(tx.execute(status -> jdbc.queryForObject(
                        "SELECT expire_unverified_accounts(?)", Integer.class,
                        Timestamp.from(Instant.now().minus(unverifiedRetention)))));
            }
        });
    }
}
