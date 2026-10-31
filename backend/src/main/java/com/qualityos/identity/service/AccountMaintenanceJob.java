package com.qualityos.identity.service;

import com.qualityos.identity.repository.AccountTokenRepository;
import com.qualityos.identity.repository.RefreshTokenRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Tokens belong to users, not tenants, so this job holds no tenant context and touches no tenant's data. */
@Component
@ConditionalOnProperty(name = "qualityos.jobs.enabled", havingValue = "true")
class AccountMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(AccountMaintenanceJob.class);

    /** Kept a while after expiry so that a late reuse of a refresh token is still recognised as reuse. */
    private static final Duration RETENTION = Duration.ofDays(7);

    private final RefreshTokenRepository refreshTokens;
    private final AccountTokenRepository accountTokens;
    private final TransactionTemplate tx;

    AccountMaintenanceJob(
            RefreshTokenRepository refreshTokens,
            AccountTokenRepository accountTokens,
            PlatformTransactionManager transactionManager) {
        this.refreshTokens = refreshTokens;
        this.accountTokens = accountTokens;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT24H")
    void purgeExpiredTokens() {
        Instant cutoff = Instant.now().minus(RETENTION);
        int removed = tx.execute(status ->
                refreshTokens.deleteExpiredBefore(cutoff) + accountTokens.deleteExpiredBefore(cutoff));
        log.info("Purged {} expired tokens", removed);
    }
}
