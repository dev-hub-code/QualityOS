package com.qualityos.reporting.service;

import com.qualityos.reporting.dto.ReportingDtos.Column;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A report whose rows another module works out in code rather than in SQL, such as OEE or production cost. The report
 * catalogue, permissions, export and audit stay with reporting; the other module only supplies the table.
 */
public interface ComputedReport {

    ReportType type();

    /**
     * @param facilityId one facility, already checked against the caller's scope, or null for every facility they may
     *     see
     */
    Table rows(LocalDate from, LocalDate to, UUID facilityId);

    /** Numeric columns hold plain numbers or null, never units or words, because the Excel export parses them. */
    record Table(List<Column> columns, List<List<String>> rows, String note) {
    }
}
