package com.qualityos.common.job;

import com.qualityos.common.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every scheduled job runs through here. Only one instance runs a job at a time, a tenant's failure does not stop the
 * others, and each run leaves a row in {@code scheduled_job_executions}.
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);
    private static final String CLAIM = """
            INSERT INTO scheduled_job_locks (job_name, locked_until, locked_at, locked_by)
            VALUES (?, now() + make_interval(secs => ?), now(), ?)
            ON CONFLICT (job_name) DO UPDATE
            SET locked_until = excluded.locked_until, locked_at = excluded.locked_at, locked_by = excluded.locked_by
            WHERE scheduled_job_locks.locked_until <= now()
            """;
    private static final String RELEASE = """
            UPDATE scheduled_job_locks SET locked_until = greatest(now(), locked_at + make_interval(secs => ?))
            WHERE job_name = ? AND locked_by = ?
            """;
    private static final String RECORD = """
            INSERT INTO scheduled_job_executions (id, job_name, instance, started_at, finished_at, duration_ms,
                tenants_processed, records_processed, notifications_generated, records_skipped, failure_count, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String instance;

    JobRunner(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${HOSTNAME:local}") String hostname) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.instance = hostname + "-" + UUID.randomUUID();
    }

    /**
     * @param lease how long the lock holds if this instance dies mid-run, so it must outlast a normal run
     * @param interval how often the job is scheduled. The lock is held at least 90% of this, so that a second instance
     *     on the same schedule, slightly out of step, does not repeat the run
     * @return whether this instance ran the job
     */
    public boolean run(String job, Duration lease, Duration interval, Consumer<JobContext> work) {
        if (!claim(job, lease)) {
            log.debug("job={} skipped: another instance holds the lock", job);
            return false;
        }
        JobContext context = new JobContext();
        Instant started = Instant.now();
        boolean completed = attempt(job, work, context);
        record(job, started, Instant.now(), context, !completed);
        // A failed run gives the lock straight back, so that the next tick can try again.
        long held = completed ? interval.toSeconds() * 9 / 10 : 0;
        tx.executeWithoutResult(status -> jdbc.update(RELEASE, held, job, instance));
        return true;
    }

    private static boolean attempt(String job, Consumer<JobContext> work, JobContext context) {
        try {
            work.accept(context);
            return true;
        } catch (RuntimeException ex) {
            context.failed();
            log.error("job={} failed", job, ex);
            return false;
        }
    }

    /** Runs the work once per tenant, each under that tenant's own context. */
    public void forEachTenant(JobContext context, Collection<UUID> tenants, Consumer<UUID> work) {
        for (UUID tenantId : tenants) {
            try {
                TenantContext.runAs(tenantId, null, () -> work.accept(tenantId));
                context.tenantProcessed();
            } catch (RuntimeException ex) {
                context.failed();
                log.error("Job failed for tenant {}", tenantId, ex);
            }
        }
    }

    private boolean claim(String job, Duration lease) {
        return Boolean.TRUE.equals(tx.execute(status -> jdbc.update(CLAIM, job, lease.toSeconds(), instance) == 1));
    }

    private void record(String job, Instant started, Instant finished, JobContext context, boolean crashed) {
        String outcome = status(context, crashed);
        UUID id = UUID.randomUUID();
        long duration = Duration.between(started, finished).toMillis();
        tx.executeWithoutResult(status -> jdbc.update(RECORD, id, job, instance,
                java.sql.Timestamp.from(started), java.sql.Timestamp.from(finished), duration,
                context.tenants(), context.records(), context.notifications(), context.skipped(), context.failures(),
                outcome));
        log.info("job={} executionId={} status={} startedAt={} finishedAt={} durationMs={} tenants={} records={} "
                        + "notifications={} skipped={} failures={}",
                job, id, outcome, started, finished, duration, context.tenants(), context.records(),
                context.notifications(), context.skipped(), context.failures());
    }

    private static String status(JobContext context, boolean crashed) {
        if (crashed || (context.failures() > 0 && context.tenants() == 0)) {
            return "FAILED";
        }
        return context.failures() > 0 ? "PARTIAL_SUCCESS" : "SUCCESS";
    }
}
