package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qualityos.common.job.JobMonitor;
import com.qualityos.common.job.JobRunner;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.quality.service.CapaService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Starts the application with its jobs on, so Spring registers the real {@code @Scheduled} methods with their real
 * intervals. Only the clock is replaced. Nothing here calls a job directly: time moves, and the job runs or does not.
 */
@Import(ScheduledJobsIT.Scheduler.class)
@TestPropertySource(properties = {"qualityos.jobs.enabled=true", "app.registration.allow-unverified-login=false"})
class ScheduledJobsIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class Scheduler {
        @Bean
        TaskScheduler taskScheduler() {
            return new ManualTaskScheduler();
        }
    }

    @Autowired
    private TaskScheduler scheduler;

    @Autowired
    private JobRunner runner;

    @Autowired
    private JobMonitor monitor;

    @MockitoSpyBean
    private CapaService capas;

    private ManualTaskScheduler clock;

    @BeforeEach
    void clean() {
        clock = (ManualTaskScheduler) scheduler;
        clock.restart();
        reset(capas);
        jdbc.update("DELETE FROM scheduled_job_locks");
        jdbc.update("DELETE FROM scheduled_job_executions");
    }

    /** Stands for the wall clock passing: locks use database time, which a simulated clock does not move. */
    private void locksLapse() {
        jdbc.update("UPDATE scheduled_job_locks SET locked_until = now()");
    }

    private void tickEverything() {
        locksLapse();
        clock.advance(Duration.ofHours(1).plusMinutes(1));
        locksLapse();
    }

    private int runs(String job) {
        return jdbc.queryForObject("SELECT count(*) FROM scheduled_job_executions WHERE job_name = ?", Integer.class, job);
    }

    private Map<String, Object> lastRun(String job) {
        return jdbc.queryForMap("SELECT * FROM scheduled_job_executions WHERE job_name = ? ORDER BY finished_at DESC LIMIT 1", job);
    }

    private String overdueCapa(Session session) {
        UUID capa = UUID.randomUUID();
        asAppRole(session.tenantId(), sql -> sql.update("""
                INSERT INTO corrective_actions (id, tenant_id, capa_number, title, problem_statement, owner_id, target_date, status)
                VALUES (?, ?, ?, 'Tool wear', 'Burrs', ?, current_date - 1, 'OPEN')
                """, capa, session.tenantId(), "CAPA-" + capa, session.userId()));
        return capa.toString();
    }

    private void overdueInstrument(Session session) throws Exception {
        String created = as(session, postJson("/api/v1/facilities",
                        Map.of("code", "F" + UUID.randomUUID().toString().substring(0, 8), "name", "Plant")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String facility = com.jayway.jsonpath.JsonPath.read(created, "$.data.id");
        as(session, postJson("/api/v1/instruments", Map.of("instrumentCode", "VC-1", "name", "Caliper",
                        "category", "Dimensional", "facilityId", facility, "frequencyDays", 180,
                        "nextDueOn", java.time.LocalDate.now().minusDays(2).toString())))
                .andExpect(status().isCreated());
    }

    private int calibrationNotices(Session session) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM notifications WHERE tenant_id = ? AND type = 'CALIBRATION_OVERDUE'",
                Integer.class, session.tenantId());
    }

    private int capaNotices(Session session) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM notifications WHERE tenant_id = ? AND type = 'CAPA_OVERDUE'",
                Integer.class, session.tenantId());
    }

    @Test
    void everyJobIsRegisteredAtStartupWithItsConfiguredInterval() {
        assertThat(clock.delays()).containsExactlyInAnyOrder(
                Duration.ofHours(6), Duration.ofSeconds(30), Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(24),
                Duration.ofMinutes(15));
    }

    @Test
    void aJobWaitsForItsDueTimeThenRunsAgainEachInterval() {
        clock.advance(Duration.ofMinutes(4));
        assertThat(runs("capa-reminders")).isZero();
        assertThat(runs("notification-delivery")).isGreaterThan(0);

        clock.advance(Duration.ofMinutes(2));
        assertThat(runs("capa-reminders")).isEqualTo(1);
        assertThat(runs("unconfirmed-shipments")).isEqualTo(1);
        assertThat(runs("account-maintenance")).isZero();

        locksLapse();
        clock.advance(Duration.ofMinutes(58));
        assertThat(runs("capa-reminders")).isEqualTo(1);
        clock.advance(Duration.ofMinutes(2));
        assertThat(runs("capa-reminders")).isEqualTo(2);
        assertThat(runs("account-maintenance")).isEqualTo(1);
    }

    @Test
    void aJobThatJustRanIsNotRepeatedByAnotherInstanceOnTheSameSchedule() {
        clock.advance(Duration.ofMinutes(6));
        assertThat(runs("capa-reminders")).isEqualTo(1);
        clock.advance(Duration.ofHours(1));
        assertThat(runs("capa-reminders")).isEqualTo(1);
    }

    @Test
    void aReminderReachesEachTenantOnlyOnce() throws Exception {
        Session one = register();
        Session two = register();
        overdueCapa(one);
        overdueCapa(two);

        tickEverything();
        assertThat(capaNotices(one)).isEqualTo(1);
        assertThat(capaNotices(two)).isEqualTo(1);
        assertThat((Integer) lastRun("capa-reminders").get("tenants_processed")).isGreaterThanOrEqualTo(2);
        assertThat((String) lastRun("capa-reminders").get("status")).isEqualTo("SUCCESS");

        tickEverything();
        assertThat(capaNotices(one)).isEqualTo(1);
        assertThat(capaNotices(two)).isEqualTo(1);
    }

    @Test
    void anOverdueInstrumentRemindsEachTenantOnceThroughTheSchedule() throws Exception {
        Session one = register();
        Session two = register();
        overdueInstrument(one);
        overdueInstrument(two);

        tickEverything();
        assertThat(calibrationNotices(one)).isEqualTo(1);
        assertThat(calibrationNotices(two)).isEqualTo(1);
        assertThat((String) lastRun("calibration-reminders").get("status")).isEqualTo("SUCCESS");
        assertThat((Integer) lastRun("calibration-reminders").get("tenants_processed")).isGreaterThanOrEqualTo(2);

        tickEverything();
        assertThat(calibrationNotices(one)).isEqualTo(1);
        assertThat(calibrationNotices(two)).isEqualTo(1);
    }

    @Test
    void oneTenantsFailureDoesNotStopTheOthers() throws Exception {
        Session broken = register();
        Session fine = register();
        overdueCapa(broken);
        overdueCapa(fine);
        doAnswer(call -> {
            if (broken.tenantId().equals(TenantContext.getCurrentTenantId())) {
                throw new IllegalStateException("broken tenant");
            }
            return call.callRealMethod();
        }).when(capas).remindOverdue();

        tickEverything();

        assertThat(capaNotices(fine)).isEqualTo(1);
        assertThat(capaNotices(broken)).isZero();
        Map<String, Object> run = lastRun("capa-reminders");
        assertThat(run.get("status")).isEqualTo("PARTIAL_SUCCESS");
        assertThat((Integer) run.get("failure_count")).isEqualTo(1);
    }

    @Test
    void aFailedRunIsRecordedAndTheNextOneRecovers() throws Exception {
        Session one = register();
        overdueCapa(one);
        doAnswer(call -> {
            throw new IllegalStateException("every tenant");
        }).when(capas).remindOverdue();
        tickEverything();
        assertThat(lastRun("capa-reminders").get("status")).isEqualTo("FAILED");
        assertThat(capaNotices(one)).isZero();

        reset(capas);
        tickEverything();
        assertThat(capaNotices(one)).isEqualTo(1);
    }

    @Test
    void anExpiredSignUpIsEmptiedAndAVerifiedAccountIsLeftAlone() throws Exception {
        Session expired = register();
        Session recent = register();
        Session active = register();
        JdbcTemplate superuser = asDatabaseSuperuser();
        superuser.update("UPDATE users SET status = 'PENDING_VERIFICATION', created_at = now() - interval '31 days' WHERE id = ?",
                expired.userId());
        superuser.update("UPDATE users SET status = 'PENDING_VERIFICATION', created_at = now() - interval '29 days' WHERE id = ?",
                recent.userId());
        superuser.update("UPDATE users SET created_at = now() - interval '300 days' WHERE id = ?", active.userId());

        tickEverything();

        Map<String, Object> gone = asDatabaseSuperuser().queryForMap(
                "SELECT email, status, full_name, password_hash FROM users WHERE id = ?", expired.userId());
        assertThat(gone.get("status")).isEqualTo("DISABLED");
        assertThat((String) gone.get("email")).endsWith("@expired.invalid");
        assertThat(gone.get("full_name")).isEqualTo("Expired sign-up");
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT status FROM tenants WHERE id = ?", String.class, expired.tenantId())).isEqualTo("SUSPENDED");
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, expired.userId())).isZero();

        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT status FROM users WHERE id = ?", String.class, recent.userId())).isEqualTo("PENDING_VERIFICATION");
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, active.userId()))
                .isEqualTo(active.email().toLowerCase());
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT status FROM tenants WHERE id = ?", String.class, active.tenantId())).isEqualTo("ACTIVE");
    }

    @Test
    void onlyOneInstanceRunsAJobAtATime() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = pool.submit(() -> runner.run("shared", Duration.ofMinutes(1), Duration.ZERO, context -> {
                inside.countDown();
                await(release);
            }));
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(runner.run("shared", Duration.ofMinutes(1), Duration.ZERO, context -> {
                throw new AssertionError("ran twice");
            })).isFalse();
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(runs("shared")).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void aLockLeftByADeadInstanceLapsesSoTheJobRecoversAfterRestart() {
        Timestamp past = Timestamp.from(Instant.now().minusSeconds(60));
        jdbc.update("INSERT INTO scheduled_job_locks VALUES ('held', now() + interval '1 hour', now(), 'dead')");
        jdbc.update("INSERT INTO scheduled_job_locks VALUES ('lapsed', ?, ?, 'dead')", past, past);

        assertThat(runner.run("held", Duration.ofMinutes(1), Duration.ZERO, context -> { })).isFalse();
        assertThat(runner.run("lapsed", Duration.ofMinutes(1), Duration.ZERO, context -> { })).isTrue();
    }

    @Test
    void aRunRecordsWhatItDid() {
        runner.run("counted", Duration.ofMinutes(1), Duration.ZERO, context -> {
            context.processed(5);
            context.notified(2);
            context.skipped(3);
        });
        Map<String, Object> run = lastRun("counted");
        assertThat(run.get("records_processed")).isEqualTo(5);
        assertThat(run.get("notifications_generated")).isEqualTo(2);
        assertThat(run.get("records_skipped")).isEqualTo(3);
        assertThat(run.get("status")).isEqualTo("SUCCESS");
        assertThat(run.get("finished_at")).isNotNull();
        assertThat(run.get("instance")).isNotNull();
    }

    @Test
    void aJobThatHasGoneQuietIsNoticed() {
        assertThat(monitor.stale(Instant.now())).isEmpty();
        tickEverything();
        assertThat(monitor.stale(Instant.now())).isEmpty();
        assertThat(monitor.stale(Instant.now().plus(Duration.ofHours(4)))).containsKeys(
                "capa-reminders", "calibration-reminders", "subscription-notices", "unconfirmed-shipments", "notification-delivery");
        assertThat(monitor.stale(Instant.now().plus(Duration.ofHours(4)))).doesNotContainKey("account-maintenance");
    }
}
