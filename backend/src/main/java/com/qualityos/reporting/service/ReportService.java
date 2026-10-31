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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Every column is read as text in the organisation's time zone, so that screen, PDF and Excel all agree. */
@Service
public class ReportService {

    private static final int MAX_ROWS = 5000;
    private static final String DATE = "to_char(%s AT TIME ZONE CAST(:zone AS text), 'YYYY-MM-DD')";
    private static final String DECIDED = "i.final_result IS NOT NULL AND i.approved_at >= :start AND i.approved_at < :end";
    private static final String LOTS = DECIDED + " AND i.receipt_item_id IS NOT NULL AND i.disposition <> 'HOLD'";
    private static final String RATE = "round(100.0 * %s / NULLIF(%s, 0), 1)::text";

    private record Definition(List<Column> columns, String sql, String note) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final ReportScope scopes;
    private final OrganizationService organizations;
    private final FacilityService facilities;
    private final AuditService audit;

    ReportService(
            NamedParameterJdbcTemplate jdbc,
            ReportScope scopes,
            OrganizationService organizations,
            FacilityService facilities,
            AuditService audit) {
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.organizations = organizations;
        this.facilities = facilities;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ReportTable view(ReportType type, LocalDate from, LocalDate to, UUID facilityId) {
        return build(type, scopes.resolve(from, to, facilityId));
    }

    /** Taking a copy of quality records out of the system leaves a trace. */
    @Transactional
    public ReportTable export(ReportType type, String format, LocalDate from, LocalDate to, UUID facilityId) {
        Resolved scope = scopes.resolve(from, to, facilityId);
        ReportTable table = build(type, scope);
        audit.record("REPORT_EXPORTED", "Report", type.slug(), null, Map.of(
                "format", format, "from", scope.from().toString(), "to", scope.to().toString(),
                "rows", table.rows().size()));
        return table;
    }

    private ReportTable build(ReportType type, Resolved scope) {
        Definition definition = definition(type, scope);
        int width = definition.columns().size();
        List<List<String>> rows = new ArrayList<>(jdbc.query(
                definition.sql() + " LIMIT " + (MAX_ROWS + 1), scope.params(), (row, n) -> {
                    List<String> values = new ArrayList<>(width);
                    for (int column = 1; column <= width; column++) {
                        values.add(row.getString(column));
                    }
                    return values;
                }));
        boolean truncated = rows.size() > MAX_ROWS;
        if (truncated) {
            rows.removeLast();
        }
        return new ReportTable(
                type.title(),
                organizations.getCurrent().name(),
                scope.facilityId() == null ? "All facilities you can see"
                        : facilities.get(scope.facilityId()).facility().name(),
                type == ReportType.CALIBRATION ? "As of " + scope.today() : scope.from() + " to " + scope.to(),
                DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z").withZone(scope.zone()).format(Instant.now()),
                definition.note(),
                definition.columns(),
                rows,
                truncated);
    }

    private static String date(String column) {
        return DATE.formatted(column);
    }

    private static Definition definition(ReportType type, Resolved scope) {
        return switch (type) {
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
                    List.of(text("Code", 14), text("Material", 34), number("Lots decided", 12),
                            number("Quantity inspected", 14), number("Quantity rejected", 14), number("Rejection %", 12)),
                    """
                    SELECT m.code, m.name, count(*)::text, trim_scale(sum(i.quantity))::text,
                           trim_scale(COALESCE(sum(i.quantity) FILTER (WHERE i.disposition = 'REJECT'), 0))::text,
                           %s
                    FROM inspections i
                    JOIN materials m ON m.tenant_id = i.tenant_id AND m.id = i.material_id
                    WHERE %s%s
                    GROUP BY m.code, m.name
                    ORDER BY COALESCE(sum(i.quantity) FILTER (WHERE i.disposition = 'REJECT'), 0) / sum(i.quantity) DESC, m.code
                    """.formatted(RATE.formatted("COALESCE(sum(i.quantity) FILTER (WHERE i.disposition = 'REJECT'), 0)",
                            "sum(i.quantity)"), LOTS, scope.facility("i.facility_id")),
                    "Quantities are added as recorded, so compare materials in the same unit.");
            case NCR -> new Definition(
                    List.of(text("NCR", 13), text("Title", 29), text("Severity", 8), text("Status", 13),
                            text("Source", 9), text("Facility", 12), text("Raised", 9), text("Closed", 9),
                            number("Age (days)", 6)),
                    """
                    SELECT n.ncr_number, n.title, n.severity, n.status, n.source, f.name, %s, %s,
                           (COALESCE((n.closed_at AT TIME ZONE CAST(:zone AS text))::date, :today)
                               - (n.created_at AT TIME ZONE CAST(:zone AS text))::date)::text
                    FROM %s
                    LEFT JOIN facilities f ON f.tenant_id = n.tenant_id AND f.id = %s
                    WHERE n.created_at >= :start AND n.created_at < :end%s ORDER BY n.created_at DESC
                    """.formatted(date("n.created_at"), date("n.closed_at"), AnalyticsService.NCRS,
                            AnalyticsService.NCR_FACILITY, scope.facility(AnalyticsService.NCR_FACILITY)),
                    "NCRs raised in the period. One raised by hand has no facility, and appears only without a"
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
                            scope.facility(AnalyticsService.NCR_FACILITY)),
                    "CAPAs opened in the period. A CAPA takes the facility of its NCR.");
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
        };
    }
}
