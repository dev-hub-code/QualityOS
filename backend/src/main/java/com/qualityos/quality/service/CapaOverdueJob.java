package com.qualityos.quality.service;

import com.qualityos.common.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs hourly so that each plant's day is noticed soon after it ends, wherever that plant is. */
@Component
@ConditionalOnProperty(name = "qualityos.jobs.enabled", havingValue = "true")
class CapaOverdueJob {

    private static final Logger log = LoggerFactory.getLogger(CapaOverdueJob.class);

    private final CapaService capas;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    CapaOverdueJob(CapaService capas, JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.capas = capas;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
    void remind() {
        List<UUID> tenants = tx.execute(status ->
                jdbc.queryForList("SELECT tenants_with_overdue_capas()", UUID.class));
        for (UUID tenantId : tenants) {
            try {
                TenantContext.runAs(tenantId, null, capas::remindOverdue);
            } catch (RuntimeException ex) {
                log.error("Overdue reminders failed for tenant {}", tenantId, ex);
            }
        }
    }
}
