package com.qualityos.reporting.service;

import com.qualityos.common.attachment.FileStorage;
import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.security.Permissions;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.common.util.DayLabels;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.notification.service.NotificationService;
import com.qualityos.notification.service.NotificationType;
import com.qualityos.reporting.dto.ReportingDtos.ReportExportView;
import com.qualityos.reporting.entity.ReportExport;
import com.qualityos.reporting.repository.ReportExportRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reports too long for the screen, exported to Excel in the background. An export runs as the person who asked for it,
 * so it holds only what they may see then: their facilities, and only while they still have the report's permission.
 * Rows go from a database cursor straight into the file, which is kept for a week and only they can download.
 */
@Service
public class ReportExports {

    private static final Logger LOG = LoggerFactory.getLogger(ReportExports.class);
    private static final Duration KEPT = Duration.ofDays(7);
    private static final Duration WAITING = Duration.ofMinutes(1);
    private static final Duration INTERRUPTED = Duration.ofMinutes(30);
    private static final int OPEN_PER_PERSON = 3;
    private static final String LINK = "/reports/exports";
    private static final String CLAIM =
            "UPDATE report_exports SET status = 'RUNNING', started_at = now() WHERE id = ? AND status = 'QUEUED'";

    private final ReportExportRepository exports;
    private final ReportService reports;
    private final ReportScope scopes;
    private final ReportExporter exporter;
    private final FileStorage storage;
    private final TenantAccessService access;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int maxRows;

    ReportExports(
            ReportExportRepository exports,
            ReportService reports,
            ReportScope scopes,
            ReportExporter exporter,
            FileStorage storage,
            TenantAccessService access,
            NotificationService notifications,
            AuditService audit,
            ApplicationEventPublisher events,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${qualityos.reports.background-rows}") int maxRows) {
        this.exports = exports;
        this.reports = reports;
        this.scopes = scopes;
        this.exporter = exporter;
        this.storage = storage;
        this.access = access;
        this.notifications = notifications;
        this.audit = audit;
        this.events = events;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.maxRows = maxRows;
    }

    /** A file to download: its name, and its bytes, opened only when sent. */
    public record Download(String fileName, long size, InputStream content) {
    }

    /** Checks the period and facility now, so that a mistake is shown at once rather than as a failed export. */
    @Transactional
    public ReportExportView request(ReportType type, LocalDate from, LocalDate to, UUID facilityId) {
        ReportScope.Resolved scope = scopes.resolve(from, to, facilityId);
        UUID user = TenantContext.getCurrentUserId();
        if (exports.countByRequestedByAndStatusIn(user, List.of(ReportExport.Status.QUEUED, ReportExport.Status.RUNNING))
                >= OPEN_PER_PERSON) {
            throw new ConflictException("You have " + OPEN_PER_PERSON
                    + " exports being made already. Wait for one to finish.");
        }
        ReportExport export = exports.saveAndFlush(
                new ReportExport(user, type.slug(), scope.from(), scope.to(), facilityId));
        audit.record("REPORT_EXPORT_REQUESTED", "Report", type.slug(), null, Map.of(
                "export", export.getId().toString(), "from", scope.from().toString(), "to", scope.to().toString()));
        events.publishEvent(new ReportExportRequested(TenantContext.getCurrentTenantId(), user, export.getId()));
        return view(export);
    }

    @Transactional(readOnly = true)
    public List<ReportExportView> mine() {
        return exports.findTop50ByRequestedByOrderByCreatedAtDesc(TenantContext.getCurrentUserId()).stream()
                .map(ReportExports::view).toList();
    }

    /** Only the person who asked, while they may still see the report. Must run with no open transaction. */
    public Download download(UUID id) {
        ReportExport export = tx.execute(status -> exports.findById(id))
                .filter(found -> found.getRequestedBy().equals(TenantContext.getCurrentUserId()))
                .orElseThrow(() -> new ResourceNotFoundException("Export", id));
        if (export.getStatus() != ReportExport.Status.READY || export.getExpiresAt().isBefore(Instant.now())) {
            throw new ConflictException("This export is not ready to download, or has expired. Export it again.");
        }
        ReportType type = ReportType.of(export.getReport());
        if (!allowed(type, export.getRequestedBy())) {
            throw new ConflictException("You no longer have the permission this report needs.");
        }
        try {
            return new Download(type.slug() + "-report-" + export.getFromDate() + "-to-" + export.getToDate() + ".xlsx",
                    export.getSizeBytes(), storage.open(export.getStorageKey()));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Makes one export, under its organisation and as the person who asked. Claimed first, so that it is made once
     * even when the job and the request both reach it. Runs outside any transaction: reading and storing a long report
     * takes a while, and its outcome is saved on its own.
     */
    public void run(UUID id) {
        if (!Boolean.TRUE.equals(tx.execute(status -> jdbc.update(CLAIM, id) == 1))) {
            return;
        }
        ReportExport export = tx.execute(status -> exports.findById(id).orElseThrow());
        ReportType type = ReportType.of(export.getReport());
        String key = "report-exports/" + export.getTenantId() + "/" + id + ".xlsx";
        Path file = null;
        try {
            if (!allowed(type, export.getRequestedBy())) {
                throw new BusinessRuleException("You no longer have the permission this report needs.");
            }
            file = Files.createTempFile("report-export-", ".xlsx");
            ReportExporter.ExcelSink sink;
            boolean truncated;
            try (OutputStream out = Files.newOutputStream(file)) {
                sink = exporter.excel(out);
                truncated = reports.stream(type, export.getFromDate(), export.getToDate(), export.getFacilityId(),
                        maxRows, sink);
                sink.finish(truncated);
            }
            storage.delete(key);
            try (InputStream in = Files.newInputStream(file)) {
                storage.store(key, in);
            }
            long size = Files.size(file);
            int rows = sink.rows();
            tx.executeWithoutResult(status -> {
                ReportExport done = exports.findById(id).orElseThrow();
                Instant now = Instant.now();
                done.ready(key, rows, truncated, size, now, now.plus(KEPT));
                exports.saveAndFlush(done);
                audit.record("REPORT_EXPORTED", "Report", type.slug(), null, Map.of(
                        "format", "EXCEL", "background", true, "from", done.getFromDate().toString(),
                        "to", done.getToDate().toString(), "rows", rows));
                notifications.notifyIncludingActor(Set.of(done.getRequestedBy()), NotificationType.REPORT_EXPORT_READY,
                        type.title() + " is ready", "Your Excel export of " + rows + " rows, "
                                + DayLabels.label(done.getFromDate()) + " to " + DayLabels.label(done.getToDate())
                                + ", can be downloaded for 7 days.", LINK, null);
            });
        } catch (BusinessRuleException | ConflictException | ResourceNotFoundException ex) {
            fail(id, type, ex.getMessage());
        } catch (IOException | RuntimeException ex) {
            LOG.error("Report export {} failed", id, ex);
            fail(id, type, "The export could not be made. Try again, or choose fewer dates.");
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ex) {
                    LOG.warn("Could not remove {}", file, ex);
                }
            }
        }
    }

    /**
     * For the job, under one organisation: makes exports a restart left waiting, gives up on ones cut off mid-run, and
     * removes files past their week.
     *
     * @return how many exports it dealt with
     */
    public int tidy() {
        Instant now = Instant.now();
        List<ReportExport> waiting = tx.execute(status ->
                exports.findByStatusAndCreatedAtBefore(ReportExport.Status.QUEUED, now.minus(WAITING)));
        waiting.forEach(export -> TenantContext.runAs(export.getTenantId(), export.getRequestedBy(),
                () -> run(export.getId())));
        List<ReportExport> cut = tx.execute(status ->
                exports.findByStatusAndStartedAtBefore(ReportExport.Status.RUNNING, now.minus(INTERRUPTED)));
        cut.forEach(export -> fail(export.getId(), ReportType.of(export.getReport()),
                "The export was interrupted. Export it again."));
        List<ReportExport> old = tx.execute(status ->
                exports.findByStatusAndExpiresAtBefore(ReportExport.Status.READY, now));
        for (ReportExport export : old) {
            try {
                storage.delete(export.getStorageKey());
            } catch (IOException ex) {
                LOG.warn("Could not remove export file {}", export.getStorageKey(), ex);
                continue;
            }
            tx.executeWithoutResult(status -> {
                ReportExport expired = exports.findById(export.getId()).orElseThrow();
                expired.expire();
                exports.saveAndFlush(expired);
            });
        }
        return waiting.size() + cut.size() + old.size();
    }

    private void fail(UUID id, ReportType type, String why) {
        tx.executeWithoutResult(status -> {
            ReportExport export = exports.findById(id).orElseThrow();
            export.fail(why, Instant.now());
            exports.saveAndFlush(export);
            notifications.notifyIncludingActor(Set.of(export.getRequestedBy()), NotificationType.REPORT_EXPORT_READY,
                    type.title() + " could not be exported", why, LINK, null);
        });
    }

    /** What signing in would give them now: their roles, narrowed by the organisation's plan. */
    private boolean allowed(ReportType type, UUID user) {
        return access.resolvePermissions(TenantContext.getCurrentTenantId(), user)
                .filter(held -> held.contains(Permissions.REPORT_VIEW) && held.contains(type.permission()))
                .isPresent();
    }

    private static ReportExportView view(ReportExport export) {
        ReportType type = ReportType.of(export.getReport());
        return new ReportExportView(export.getId(), type.slug(), type.title(), export.getFromDate(),
                export.getToDate(), export.getStatus().name(), export.getRowCount(), export.isTruncated(),
                export.getSizeBytes(), export.getFailure(), export.getCreatedAt(), export.getExpiresAt());
    }
}
