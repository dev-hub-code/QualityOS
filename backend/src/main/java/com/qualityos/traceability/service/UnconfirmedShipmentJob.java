package com.qualityos.traceability.service;

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

/** Nobody is told when goods leave without a confirmation, so this asks about dispatches that have sat too long. */
@Component
@ConditionalOnProperty(name = "qualityos.jobs.enabled", havingValue = "true")
class UnconfirmedShipmentJob {

    private static final Logger log = LoggerFactory.getLogger(UnconfirmedShipmentJob.class);

    private final DispatchService dispatches;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    UnconfirmedShipmentJob(DispatchService dispatches, JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.dispatches = dispatches;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(initialDelayString = "PT5M", fixedDelayString = "PT1H")
    void remind() {
        List<UUID> tenants = tx.execute(status ->
                jdbc.queryForList("SELECT tenants_with_unconfirmed_dispatches()", UUID.class));
        for (UUID tenantId : tenants) {
            try {
                TenantContext.runAs(tenantId, null, dispatches::remindUnconfirmed);
            } catch (RuntimeException ex) {
                log.error("Shipment reminders failed for tenant {}", tenantId, ex);
            }
        }
    }
}
