package com.qualityos.reporting.service;

import static com.qualityos.reporting.dto.ReportingDtos.Column.number;
import static com.qualityos.reporting.dto.ReportingDtos.Column.text;

import com.qualityos.common.audit.AuditService;
import com.qualityos.organization.service.FacilityService;
import com.qualityos.organization.service.OrganizationService;
import com.qualityos.reporting.dto.ReportingDtos.Column;
import com.qualityos.reporting.dto.ReportingDtos.ReportTable;
import com.qualityos.reporting.service.ReportScope.Resolved;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Every column is read as text in the organisation's time zone, so that screen, PDF and Excel all agree. */
@Service
public class ReportService {

    private static final String DATE = "to_char(%s AT TIME ZONE CAST(:zone AS text), 'YYYY-MM-DD')";
    private static final String DECIDED = "i.final_result IS NOT NULL AND i.approved_at >= :start AND i.approved_at < :end";
    private static final String LOTS = DECIDED + " AND i.receipt_item_id IS NOT NULL AND i.disposition <> 'HOLD'";
    private static final String RATE = "round(100.0 * %s / NULLIF(%s, 0), 1)::text";

    private record Definition(List<Column> columns, String sql, String note) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    /** The same connections, read through a cursor a few thousand rows at a time. */
    private final NamedParameterJdbcTemplate streaming;
    private final ReportScope scopes;
    private final OrganizationService organizations;
    private final FacilityService facilities;
    private final AuditService audit;
    private final Map<ReportType, ComputedReport> computed = new EnumMap<>(ReportType.class);
    private final int screenRows;
    private final int excelRows;

    ReportService(
            NamedParameterJdbcTemplate jdbc,
            ReportScope scopes,
            OrganizationService organizations,
            FacilityService facilities,
            AuditService audit,
            List<ComputedReport> computedReports,
            @Value("${qualityos.reports.screen-rows}") int screenRows,
            @Value("${qualityos.reports.excel-rows}") int excelRows) {
        this.screenRows = screenRows;
        this.excelRows = excelRows;
        computedReports.forEach(report -> computed.put(report.type(), report));
        this.jdbc = jdbc;
        JdbcTemplate cursor = new JdbcTemplate(jdbc.getJdbcTemplate().getDataSource());
        cursor.setFetchSize(2_000);
        this.streaming = new NamedParameterJdbcTemplate(cursor);
        this.scopes = scopes;
        this.organizations = organizations;
        this.facilities = facilities;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ReportTable view(ReportType type, LocalDate from, LocalDate to, UUID facilityId) {
        return build(type, scopes.resolve(from, to, facilityId), screenRows);
    }

    /**
     * Taking a copy of quality records out of the system leaves a trace. A PDF stops where the screen does, since it is
     * read page by page; an Excel download holds many more rows, for a long period that would otherwise be split.
     */
    @Transactional
    public ReportTable export(ReportType type, String format, LocalDate from, LocalDate to, UUID facilityId) {
        Resolved scope = scopes.resolve(from, to, facilityId);
        ReportTable table = build(type, scope, "EXCEL".equals(format) ? excelRows : screenRows);
        audit.record("REPORT_EXPORTED", "Report", type.slug(), null, Map.of(
                "format", format, "from", scope.from().toString(), "to", scope.to().toString(),
                "rows", table.rows().size()));
        return table;
    }

    /**
     * Reads a report row by row into {@code sink}, as the person in the current context: their facilities, today's
     * dates. Nothing is held but the row being read, so it suits an export of any length.
     *
     * @return whether rows were left out at {@code limit}
     */
    @Transactional(readOnly = true)
    public boolean stream(ReportType type, LocalDate from, LocalDate to, UUID facilityId, int limit, ReportSink sink) {
        return read(type, scopes.resolve(from, to, facilityId), limit, sink);
    }

    private static final java.util.Set<String> WORDED = java.util.Set.of("Type", "Result", "Disposition", "Severity",
            "Status", "Source", "Category", "Last result");

    private ReportTable build(ReportType type, Resolved scope, int limit) {
        List<List<String>> rows = new ArrayList<>();
        ReportTable[] heading = new ReportTable[1];
        boolean truncated = read(type, scope, limit, new ReportSink() {
            @Override
            public void start(ReportTable table) {
                heading[0] = table;
            }

            @Override
            public void row(List<String> values) {
                rows.add(values);
            }
        });
        ReportTable head = heading[0];
        return new ReportTable(head.title(), head.organizationName(), head.facilityLabel(), head.periodLabel(),
                head.generatedLabel(), head.note(), head.columns(), rows, truncated);
    }

    private boolean read(ReportType type, Resolved scope, int limit, ReportSink sink) {
        int[] count = {0};
        boolean[] more = {false};
        java.util.function.Consumer<List<String>> capped = values -> {
            if (count[0] < limit) {
                sink.row(values);
                count[0]++;
            } else {
                more[0] = true;
            }
        };
        ComputedReport source = computed.get(type);
        if (source != null) {
            ComputedReport.Table table = source.rows(scope.from(), scope.to(), scope.facilityId());
            sink.start(heading(type, scope, table.columns(), table.note()));
            table.rows().forEach(capped);
        } else {
            Definition definition = definition(type, scope);
            int width = definition.columns().size();
            sink.start(heading(type, scope, definition.columns(), definition.note()));
            // Columns that hold a stored state or kind are shown in words, not as PASS or CONDITIONAL_ACCEPTANCE.
            boolean[] worded = new boolean[width];
            for (int column = 0; column < width; column++) {
                worded[column] = WORDED.contains(definition.columns().get(column).label());
            }
            streaming.query(definition.sql() + " LIMIT " + (limit + 1L), scope.params(), (RowCallbackHandler) row -> {
                List<String> values = new ArrayList<>(width);
                for (int column = 1; column <= width; column++) {
                    String value = row.getString(column);
                    values.add(worded[column - 1] ? com.qualityos.common.util.EnumLabels.label(value) : value);
                }
                capped.accept(values);
            });
        }
        return more[0];
    }

    private ReportTable heading(ReportType type, Resolved scope, List<Column> columns, String note) {
        return new ReportTable(
                type.title(),
                organizations.getCurrent().name(),
                scope.facilityId() == null ? "All facilities you can see"
                        : facilities.get(scope.facilityId()).facility().name(),
                type == ReportType.CALIBRATION ? "As of " + scope.today() : scope.from() + " to " + scope.to(),
                DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z").withZone(scope.zone()).format(Instant.now()),
                note,
                columns,
                List.of(),
                false);
    }

    private static String date(String column) {
        return DATE.formatted(column);
    }

    private static Definition definition(ReportType type, Resolved scope) {
        return switch (type) {
            case OEE, PRODUCTION_COST -> throw new IllegalStateException(type + " is worked out in code.");
            case INSPECTION -> new Definition(
                    List.of(text("Inspection", 13), text("Type", 6), text("Decided", 9), text("Batch", 14),
                            text("Supplier", 15), text("Item", 17), text("Facility", 12), text("Result", 6),
                            text("Disposition", 11)),
                    """
                    SELECT i.inspection_number, i.inspection_type, %s, i.batch_number, s.name, COALESCE(m.name, p.name),
                           f.name, i.final_result, i.disposition
                    FROM inspections i
                    LEFT JOIN suppliers s ON s.tenant_id = i.tenant_id AND s.id = i.supplier_id
                    LEFT JOIN materials m ON m.tenant_id = i.tenant_id AND m.id = i.material_id
                    LEFT JOIN production_batches b ON b.tenant_id = i.tenant_id AND b.id = i.production_batch_id
                    LEFT JOIN products p ON p.tenant_id = b.tenant_id AND p.id = b.product_id
                    LEFT JOIN facilities f ON f.tenant_id = i.tenant_id AND f.id = i.facility_id
                    WHERE %s%s ORDER BY i.approved_at DESC
                    """.formatted(date("i.approved_at"), DECIDED, scope.facility("i.facility_id")),
                    "Inspections decided in the period.");
            case SUPPLIER_QUALITY -> new Definition(
                    List.of(text("Supplier", 34), number("Lots decided", 11), number("Accepted", 11),
                            number("Rejected", 11), number("Rejection %", 11), number("NCRs raised", 11),
                            number("NCRs open", 11)),
                    """
                    SELECT s.name, l.lots::text, l.accepted::text, l.rejected::text, %s,
                           COALESCE(c.ncrs, 0)::text, COALESCE(c.open, 0)::text
                    FROM (SELECT i.tenant_id, i.supplier_id, count(*) AS lots,
                                 count(*) FILTER (WHERE i.disposition IN ('ACCEPT', 'CONDITIONAL_ACCEPTANCE')) AS accepted,
                                 count(*) FILTER (WHERE i.disposition = 'REJECT') AS rejected
                          FROM inspections i WHERE %s%s GROUP BY 1, 2) l
                    JOIN suppliers s ON s.tenant_id = l.tenant_id AND s.id = l.supplier_id
                    LEFT JOIN (SELECT n.supplier_id, count(*) AS ncrs, count(*) FILTER (WHERE %s) AS open
                               FROM %s WHERE n.created_at >= :start AND n.created_at < :end%s GROUP BY 1) c
                           ON c.supplier_id = l.supplier_id
                    ORDER BY l.rejected::numeric / l.lots DESC, s.name
                    """.formatted(RATE.formatted("l.rejected", "l.lots"), LOTS, scope.facility("i.facility_id"),
                            AnalyticsService.NCR_OPEN, AnalyticsService.NCRS,
                            scope.facility(AnalyticsService.NCR_FACILITY)),
                    "Lots whose receiving inspection reached a final disposition in the period.");
            case MATERIAL_REJECTION -> new Definition(
                    List.of(text("Code", 14), text("Material", 28), text("Unit", 6), number("Lots decided", 12),
                            number("Quantity inspected", 14), number("Quantity rejected", 14), number("Rejection %", 12)),
                    """
                    SELECT m.code, m.name, u.code, count(*)::text, trim_scale(round(sum(q.quantity), 6))::text,
                           trim_scale(round(COALESCE(sum(q.quantity) FILTER (WHERE i.disposition = 'REJECT'), 0), 6))::text,
                           %s
                    FROM inspections i
                    JOIN materials m ON m.tenant_id = i.tenant_id AND m.id = i.material_id
                    JOIN material_receipt_items r ON r.tenant_id = i.tenant_id AND r.id = i.receipt_item_id
                    CROSS JOIN LATERAL (SELECT convert_quantity(i.quantity, r.uom_id, m.uom_id) AS converted) c
                    CROSS JOIN LATERAL (SELECT COALESCE(c.converted, i.quantity) AS quantity,
                                               CASE WHEN c.converted IS NULL THEN r.uom_id ELSE m.uom_id END AS uom_id) q
                    JOIN units_of_measure u ON u.id = q.uom_id
                    WHERE %s%s
                    GROUP BY m.code, m.name, u.code
                    ORDER BY COALESCE(sum(q.quantity) FILTER (WHERE i.disposition = 'REJECT'), 0) / sum(q.quantity) DESC,
                             m.code, u.code
                    """.formatted(RATE.formatted("COALESCE(sum(q.quantity) FILTER (WHERE i.disposition = 'REJECT'), 0)",
                            "sum(q.quantity)"), LOTS, scope.facility("i.facility_id")),
                    "Quantities are in the material's own unit. A lot received in a unit that does not convert to"
                            + " it has a row of its own.");
            case NCR -> new Definition(
                    List.of(text("NCR", 13), text("Title", 29), text("Severity", 8), text("Status", 13),
                            text("Source", 9), text("Facility", 12), text("Raised", 9), text("Closed", 9),
                            number("Age (days)", 6)),
                    """
                    SELECT n.ncr_number, n.title, n.severity, COALESCE(sl.label, n.status), n.source, f.name, %s, %s,
                           (COALESCE((n.closed_at AT TIME ZONE CAST(:zone AS text))::date, :today)
                               - (n.created_at AT TIME ZONE CAST(:zone AS text))::date)::text
                    FROM %s
                    LEFT JOIN facilities f ON f.tenant_id = n.tenant_id AND f.id = %s
                    LEFT JOIN ncr_status_labels sl ON sl.tenant_id = n.tenant_id AND sl.status = n.status
                    WHERE n.created_at >= :start AND n.created_at < :end%s ORDER BY n.created_at DESC
                    """.formatted(date("n.created_at"), date("n.closed_at"), AnalyticsService.NCRS,
                            AnalyticsService.NCR_FACILITY, scope.facility(AnalyticsService.NCR_FACILITY)),
                    "NCRs raised in the period. One raised by hand without a facility appears only without a"
                            + " facility limit.");
            case CAPA -> new Definition(
                    List.of(text("CAPA", 13), text("Title", 33), text("Status", 15), text("NCR", 13), text("Target", 9),
                            text("Closed", 9), text("Overdue", 8)),
                    """
                    SELECT c.capa_number, c.title, c.status, n.ncr_number, c.target_date::text, %s,
                           CASE WHEN %s AND c.target_date < :today THEN 'Yes' ELSE 'No' END
                    FROM %s
                    WHERE c.created_at >= :start AND c.created_at < :end%s ORDER BY c.created_at DESC
                    """.formatted(date("c.closed_at"), AnalyticsService.CAPA_OPEN, AnalyticsService.CAPAS,
                            scope.facility(AnalyticsService.CAPA_FACILITY)),
                    "CAPAs opened in the period. A CAPA takes the facility of its NCR, or the one chosen for it.");
            case CALIBRATION -> new Definition(
                    List.of(text("Instrument", 12), text("Name", 22), text("Category", 13), text("Facility", 14),
                            text("Status", 12), text("Last result", 9), text("Last calibrated", 9), text("Next due", 9)),
                    """
                    SELECT t.instrument_code, t.name, t.category, f.name, t.status,
                           (SELECT r.result FROM calibration_records r
                            WHERE r.tenant_id = t.tenant_id AND r.instrument_id = t.id
                            ORDER BY r.calibrated_on DESC, r.created_at DESC LIMIT 1),
                           t.last_calibrated_on::text, t.next_due_on::text
                    FROM instruments t
                    JOIN facilities f ON f.tenant_id = t.tenant_id AND f.id = t.facility_id
                    WHERE t.status <> 'RETIRED'%s ORDER BY t.next_due_on, t.instrument_code
                    """.formatted(scope.facility("t.facility_id")),
                    "The register as it stands today, soonest due first. The period does not apply.");
            case AUDIT -> new Definition(
                    List.of(text("Audit", 13), text("Type", 9), text("Title", 30), text("Facility", 14),
                            text("Planned", 10), text("Status", 12), number("Findings", 6), number("NCRs open", 6)),
                    """
                    SELECT a.audit_number, a.audit_type, a.title, f.name, a.planned_on::text, a.status,
                           (SELECT count(*) FROM audit_findings x WHERE x.tenant_id = a.tenant_id AND x.audit_id = a.id)::text,
                           (SELECT count(*) FROM non_conformances n WHERE n.tenant_id = a.tenant_id AND n.audit_id = a.id
                              AND %s)::text
                    FROM audits a
                    JOIN facilities f ON f.tenant_id = a.tenant_id AND f.id = a.facility_id
                    WHERE a.planned_on BETWEEN CAST(:from AS date) AND CAST(:to AS date)%s
                    ORDER BY a.planned_on DESC
                    """.formatted(AnalyticsService.NCR_OPEN, scope.facility("a.facility_id")),
                    "Audits planned for the period. Each has a full report of its own.");
            case BATCH_TRACEABILITY -> new Definition(
                    List.of(text("Batch", 13), text("Product", 18), text("Facility", 12), text("Status", 9),
                            number("Produced", 8), number("Dispatched", 8), text("Lots used", 16), text("Customers", 16)),
                    """
                    SELECT b.batch_number, p.name, f.name, b.status, trim_scale(b.produced_quantity)::text,
                           trim_scale(b.dispatched_quantity)::text,
                           (SELECT string_agg(DISTINCT l.internal_batch, ', ') FROM batch_relationships r
                            JOIN material_receipt_items l ON l.tenant_id = r.tenant_id AND l.id = r.parent_lot_id
                            WHERE r.tenant_id = b.tenant_id AND r.child_batch_id = b.id),
                           (SELECT string_agg(DISTINCT c.name, ', ') FROM dispatch_items d
                            JOIN dispatch_records h ON h.tenant_id = d.tenant_id AND h.id = d.dispatch_id
                            JOIN customers c ON c.tenant_id = h.tenant_id AND c.id = h.customer_id
                            WHERE d.tenant_id = b.tenant_id AND d.production_batch_id = b.id)
                    FROM production_batches b
                    JOIN products p ON p.tenant_id = b.tenant_id AND p.id = b.product_id
                    JOIN facilities f ON f.tenant_id = b.tenant_id AND f.id = b.facility_id
                    WHERE b.created_at >= :start AND b.created_at < :end%s ORDER BY b.created_at DESC
                    """.formatted(scope.facility("b.facility_id")),
                    "Batches started in the period, with one level of lots and customers. Customers include those"
                            + " who later returned goods. A full trace is on each batch.");
            case WORK_ORDERS -> new Definition(
                    List.of(text("Work order", 13), text("Product", 18), text("Facility", 12), text("Status", 11),
                            number("Ordered", 8), number("Received", 8), text("Unit", 5), text("Planned end", 9),
                            text("Completed", 9), text("On time", 6)),
                    """
                    SELECT w.work_order_number, p.name, f.name, w.status, trim_scale(w.quantity)::text,
                           trim_scale(r.quantity)::text, u.code, w.planned_end::text, %s,
                           CASE WHEN w.completed_at IS NULL THEN
                                    CASE WHEN w.status IN %s AND w.planned_end < :today THEN 'Late' ELSE '' END
                                WHEN (w.completed_at AT TIME ZONE CAST(:zone AS text))::date <= w.planned_end THEN 'Yes'
                                ELSE 'No' END
                    FROM work_orders w
                    JOIN products p ON p.tenant_id = w.tenant_id AND p.id = w.product_id
                    JOIN facilities f ON f.tenant_id = w.tenant_id AND f.id = w.facility_id
                    JOIN units_of_measure u ON u.id = w.uom_id
                    LEFT JOIN finished_goods_receipts r ON r.tenant_id = w.tenant_id AND r.work_order_id = w.id
                    WHERE w.status <> 'DRAFT' AND w.planned_start <= CAST(:to AS date)
                      AND w.planned_end >= CAST(:from AS date)%s
                    ORDER BY w.planned_end, w.work_order_number
                    """.formatted(date("w.completed_at"), AnalyticsService.WORK_ORDER_RUNNING,
                            scope.facility("w.facility_id")),
                    "Work orders planned to run at any time in the period, drafts left out. Late means still open"
                            + " after its planned end.");
            case PRODUCTION_QUALITY -> new Definition(
                    List.of(text("Product", 20), text("Operation", 16), text("Work centre", 14), text("Unit", 5),
                            number("Processed", 9), number("Good", 9), number("Rejected", 9), number("Scrap", 9),
                            number("Rework", 9), number("Loss %", 7)),
                    """
                    SELECT p.name, o.sequence || ' ' || o.name, c.name, u.code,
                           trim_scale(sum(r.good_quantity + r.rejected_quantity + r.scrap_quantity))::text,
                           trim_scale(sum(r.good_quantity))::text, trim_scale(sum(r.rejected_quantity))::text,
                           trim_scale(sum(r.scrap_quantity))::text, trim_scale(sum(r.rework_quantity))::text,
                           %s
                    FROM operation_reports r
                    JOIN work_orders w ON w.tenant_id = r.tenant_id AND w.id = r.work_order_id
                    JOIN work_order_operations o ON o.tenant_id = r.tenant_id AND o.id = r.operation_id
                    JOIN work_centers c ON c.tenant_id = o.tenant_id AND c.id = o.work_center_id
                    JOIN products p ON p.tenant_id = w.tenant_id AND p.id = w.product_id
                    JOIN units_of_measure u ON u.id = w.uom_id
                    WHERE r.created_at >= :start AND r.created_at < :end%s
                    GROUP BY p.name, o.sequence, o.name, c.name, u.code
                    HAVING sum(r.good_quantity + r.rejected_quantity + r.scrap_quantity) <> 0
                    ORDER BY sum(r.rejected_quantity + r.scrap_quantity)
                                 / sum(r.good_quantity + r.rejected_quantity + r.scrap_quantity) DESC, p.name, o.sequence
                    """.formatted(RATE.formatted("sum(r.rejected_quantity + r.scrap_quantity)",
                            "sum(r.good_quantity + r.rejected_quantity + r.scrap_quantity)"),
                            scope.facility("w.facility_id")),
                    "Shop-floor reports made in the period, by product and operation, with reversals netted out."
                            + " Loss is rejected plus scrap over processed.");
            case DOWNTIME -> new Definition(
                    List.of(text("Started", 11), text("Ended", 11), text("Facility", 11), text("Work centre", 11),
                            text("Machine", 9), text("Category", 13), text("Planned", 6), number("Minutes", 6),
                            text("Department", 10), text("Reason", 18)),
                    """
                    SELECT to_char(d.started_at AT TIME ZONE CAST(:zone AS text), 'YYYY-MM-DD HH24:MI'),
                           COALESCE(to_char(d.ended_at AT TIME ZONE CAST(:zone AS text), 'YYYY-MM-DD HH24:MI'), 'Open'),
                           f.name, c.name, m.code, d.category, CASE WHEN d.planned THEN 'Yes' ELSE 'No' END,
                           round(extract(epoch FROM COALESCE(d.ended_at, now()) - d.started_at) / 60)::text,
                           d.responsible_department, d.reason
                    FROM downtime_records d
                    JOIN facilities f ON f.tenant_id = d.tenant_id AND f.id = d.facility_id
                    JOIN work_centers c ON c.tenant_id = d.tenant_id AND c.id = d.work_center_id
                    LEFT JOIN machines m ON m.tenant_id = d.tenant_id AND m.id = d.machine_id
                    WHERE d.started_at >= :start AND d.started_at < :end%s
                    ORDER BY d.started_at DESC
                    """.formatted(scope.facility("d.facility_id")),
                    "Stops that began in the period. An open stop counts to now.");
        };
    }
}
