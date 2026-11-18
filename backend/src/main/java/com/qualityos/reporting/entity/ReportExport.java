package com.qualityos.reporting.entity;

import com.qualityos.common.persistence.TenantOwnedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A report exported in the background for the person who asked, and kept for them to download for a while. */
@Getter
@Entity
@Table(name = "report_exports")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportExport extends TenantOwnedEntity {

    public enum Status { QUEUED, RUNNING, READY, FAILED, EXPIRED }

    private UUID requestedBy;
    private String report;
    private LocalDate fromDate;
    private LocalDate toDate;
    private UUID facilityId;
    @Enumerated(EnumType.STRING)
    private Status status = Status.QUEUED;
    private Integer rowCount;
    private boolean truncated;
    private Long sizeBytes;
    private String storageKey;
    private String failure;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant expiresAt;

    public ReportExport(UUID requestedBy, String report, LocalDate fromDate, LocalDate toDate, UUID facilityId) {
        this.requestedBy = requestedBy;
        this.report = report;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.facilityId = facilityId;
    }

    public void ready(String key, int rows, boolean cut, long size, Instant now, Instant until) {
        status = Status.READY;
        storageKey = key;
        rowCount = rows;
        truncated = cut;
        sizeBytes = size;
        finishedAt = now;
        expiresAt = until;
    }

    public void fail(String why, Instant now) {
        status = Status.FAILED;
        failure = why;
        finishedAt = now;
    }

    public void expire() {
        status = Status.EXPIRED;
    }
}
