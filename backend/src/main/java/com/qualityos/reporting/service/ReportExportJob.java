package com.qualityos.reporting.service;

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

/** Makes exports a restart left waiting, and clears away expired ones. Exports normally start the moment they are asked for. */
@Component
@ConditionalOnProperty(name = "qualityos.jobs.enabled", havingValue = "true")
class ReportExportJob {

    private static final Duration LEASE = Duration.ofHours(1);

    private final ReportExports exports;
    private final JobRunner jobs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration interval;

    ReportExportJob(
            ReportExports exports,
            JobRunner jobs,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${qualityos.jobs.report-exports.interval}") Duration interval) {
        this.exports = exports;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.interval = interval;
    }

    @Scheduled(
            initialDelayString = "${qualityos.jobs.report-exports.initial-delay}",
            fixedDelayString = "${qualityos.jobs.report-exports.interval}")
    void tidy() {
        jobs.run("report-exports", LEASE, interval, context -> {
            List<UUID> tenants = tx.execute(status ->
                    jdbc.queryForList("SELECT tenants_with_report_exports_to_tidy()", UUID.class));
            jobs.forEachTenant(context, tenants, tenant -> context.processed(exports.tidy()));
        });
    }
}
