package com.qualityos.reporting.service;

import com.qualityos.reporting.dto.ReportingDtos.AgeBucket;
import com.qualityos.reporting.dto.ReportingDtos.CapaMonth;
import com.qualityos.reporting.dto.ReportingDtos.DefectShare;
import com.qualityos.reporting.dto.ReportingDtos.Kpis;
import com.qualityos.reporting.dto.ReportingDtos.MonthCount;
import com.qualityos.reporting.dto.ReportingDtos.Overview;
import com.qualityos.reporting.dto.ReportingDtos.Pending;
import com.qualityos.reporting.dto.ReportingDtos.QualityShare;
import com.qualityos.reporting.dto.ReportingDtos.RecentActivity;
import com.qualityos.reporting.dto.ReportingDtos.SupplierScore;
import com.qualityos.reporting.service.ReportScope.Resolved;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads across modules, so it is plain SQL. Row-level security limits every statement to the tenant, and
 * {@link ReportScope} adds the member's facilities. How each figure is defined is written in docs/ANALYTICS.md.
 */
@Service
public class AnalyticsService {

    /** An NCR belongs to the facility of what raised it. A manual NCR has none. */
    static final String NCRS = """
            non_conformances n
            LEFT JOIN inspections ni ON ni.tenant_id = n.tenant_id AND ni.id = n.inspection_id
            LEFT JOIN audits na ON na.tenant_id = n.tenant_id AND na.id = n.audit_id
            """;
    static final String NCR_FACILITY = "COALESCE(ni.facility_id, na.facility_id)";
    static final String NCR_OPEN = "n.status NOT IN ('CLOSED', 'REJECTED', 'CANCELLED')";
    /** A CAPA belongs to the facility of its NCR. */
    static final String CAPAS = """
            corrective_actions c
            LEFT JOIN non_conformances n ON n.tenant_id = c.tenant_id AND n.id = c.ncr_id
            LEFT JOIN inspections ni ON ni.tenant_id = n.tenant_id AND ni.id = n.inspection_id
            LEFT JOIN audits na ON na.tenant_id = n.tenant_id AND na.id = n.audit_id
            """;
    static final String CAPA_OPEN = "c.status NOT IN ('CLOSED', 'CANCELLED')";
    /** Decided in the period. */
    private static final String DECIDED = "i.final_result IS NOT NULL AND i.approved_at >= :start AND i.approved_at < :end";
    /** Lots whose receiving inspection ended in a final disposition in the period. */
    private static final String LOTS = DECIDED + " AND i.receipt_item_id IS NOT NULL AND i.disposition <> 'HOLD'";
    private static final int TOP = 10;

    private final NamedParameterJdbcTemplate jdbc;
    private final ReportScope scopes;

    AnalyticsService(NamedParameterJdbcTemplate jdbc, ReportScope scopes) {
        this.jdbc = jdbc;
        this.scopes = scopes;
    }

    @Transactional(readOnly = true)
    public Overview overview(LocalDate from, LocalDate to, UUID facilityId) {
        Resolved scope = scopes.resolve(from, to, facilityId);
        MapSqlParameterSource params = scope.params();
        return new Overview(scope.from(), scope.to(), kpis(scope, params), inspectionTrend(scope, params),
                suppliers(scope, params), defects(scope, params), products(scope, params), facilities(scope, params),
                ncrAgeing(scope, params), capaTrend(scope, params), pending(scope, params), recent(scope, params));
    }

    private Kpis kpis(Resolved scope, MapSqlParameterSource params) {
        Map<String, Object> inspections = jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE i.final_result = 'PASS') AS passed,
                       count(*) FILTER (WHERE i.final_result = 'FAIL') AS failed,
                       avg(extract(epoch FROM i.approved_at - i.created_at)) / 3600 AS hours
                FROM inspections i WHERE %s%s
                """.formatted(DECIDED, scope.facility("i.facility_id")), params);
        Map<String, Object> lots = jdbc.queryForMap("""
                SELECT count(*) AS lots,
                       count(*) FILTER (WHERE i.disposition = 'REJECT') AS rejected,
                       sum(i.quantity) AS quantity,
                       sum(i.quantity) FILTER (WHERE i.disposition = 'REJECT') AS rejected_quantity
                FROM inspections i WHERE %s%s
                """.formatted(LOTS, scope.facility("i.facility_id")), params);
        Map<String, Object> results = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE r.evaluation = 'FAIL') AS failed,
                       count(*) FILTER (WHERE r.evaluation IN ('PASS', 'FAIL')) AS measured
                FROM inspections i
                JOIN inspection_results r ON r.tenant_id = i.tenant_id AND r.inspection_id = i.id
                WHERE %s%s
                """.formatted(DECIDED, scope.facility("i.facility_id")), params);
        Map<String, Object> firstPass = jdbc.queryForMap("""
                SELECT count(*) AS batches, count(*) FILTER (WHERE f.final_result = 'PASS') AS passed
                FROM (SELECT DISTINCT ON (i.production_batch_id) i.final_result, i.approved_at
                      FROM inspections i
                      WHERE i.production_batch_id IS NOT NULL AND i.return_item_id IS NULL
                        AND i.final_result IS NOT NULL%s
                      ORDER BY i.production_batch_id, i.approved_at) f
                WHERE f.approved_at >= :start AND f.approved_at < :end
                """.formatted(scope.facility("i.facility_id")), params);
        Map<String, Object> ncrs = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE %s) AS open,
                       count(*) FILTER (WHERE n.closed_at >= :start AND n.closed_at < :end) AS closed
                FROM %s WHERE TRUE%s
                """.formatted(NCR_OPEN, NCRS, scope.facility(NCR_FACILITY)), params);
        Map<String, Object> capas = jdbc.queryForMap("""
                SELECT count(*) AS open, count(*) FILTER (WHERE c.target_date < :today) AS overdue
                FROM %s WHERE %s%s
                """.formatted(CAPAS, CAPA_OPEN, scope.facility(NCR_FACILITY)), params);
        long total = count(inspections, "total");
        long passed = count(inspections, "passed");
        return new Kpis(
                total,
                passed,
                count(inspections, "failed"),
                rate(passed, total),
                count(ncrs, "open"),
                count(ncrs, "closed"),
                count(capas, "open"),
                count(capas, "overdue"),
                rate(count(lots, "rejected"), count(lots, "lots")),
                rate((BigDecimal) lots.get("rejected_quantity"), (BigDecimal) lots.get("quantity")),
                rate(count(results, "failed"), count(results, "measured")),
                rate(count(firstPass, "passed"), count(firstPass, "batches")),
                inspections.get("hours") == null ? null : round(((Number) inspections.get("hours")).doubleValue()));
    }

    private List<MonthCount> inspectionTrend(Resolved scope, MapSqlParameterSource params) {
        Map<String, MonthCount> found = new HashMap<>();
        jdbc.query("""
                SELECT to_char(i.approved_at AT TIME ZONE CAST(:zone AS text), 'YYYY-MM') AS month,
                       count(*) FILTER (WHERE i.final_result = 'PASS') AS passed,
                       count(*) FILTER (WHERE i.final_result = 'FAIL') AS failed,
                       count(*) FILTER (WHERE i.receipt_item_id IS NOT NULL AND i.disposition = 'REJECT') AS rejected
                FROM inspections i WHERE %s%s
                GROUP BY 1 ORDER BY 1
                """.formatted(DECIDED, scope.facility("i.facility_id")), params,
                row -> {
                    found.put(row.getString("month"), new MonthCount(row.getString("month"), row.getLong("passed"),
                            row.getLong("failed"), row.getLong("rejected")));
                });
        return months(scope).stream().map(month -> found.getOrDefault(month, new MonthCount(month, 0, 0, 0))).toList();
    }

    private List<SupplierScore> suppliers(Resolved scope, MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT s.name, l.lots, l.rejected, COALESCE(c.ncrs, 0) AS ncrs, COALESCE(c.open, 0) AS open
                FROM (SELECT i.tenant_id, i.supplier_id, count(*) AS lots,
                             count(*) FILTER (WHERE i.disposition = 'REJECT') AS rejected
                      FROM inspections i WHERE %s%s GROUP BY 1, 2) l
                JOIN suppliers s ON s.tenant_id = l.tenant_id AND s.id = l.supplier_id
                LEFT JOIN (SELECT n.supplier_id, count(*) AS ncrs, count(*) FILTER (WHERE %s) AS open
                           FROM %s WHERE n.created_at >= :start AND n.created_at < :end%s GROUP BY 1) c
                       ON c.supplier_id = l.supplier_id
                ORDER BY l.rejected::numeric / l.lots DESC, l.lots DESC, s.name
                LIMIT %d
                """.formatted(LOTS, scope.facility("i.facility_id"), NCR_OPEN, NCRS, scope.facility(NCR_FACILITY), TOP),
                params,
                (row, n) -> new SupplierScore(row.getString("name"), row.getLong("lots"), row.getLong("rejected"),
                        rate(row.getLong("rejected"), row.getLong("lots")), row.getLong("ncrs"), row.getLong("open")));
    }

    /** Sorted largest first, so one list gives both the Pareto view and the distribution. */
    private List<DefectShare> defects(Resolved scope, MapSqlParameterSource params) {
        record Row(String category, long ncrs) {
        }
        List<Row> rows = jdbc.query("""
                SELECT COALESCE(d.name, 'Not categorised') AS category, count(*) AS ncrs
                FROM %s
                LEFT JOIN defect_categories d ON d.tenant_id = n.tenant_id AND d.id = n.defect_category_id
                WHERE n.status NOT IN ('REJECTED', 'CANCELLED') AND n.created_at >= :start AND n.created_at < :end%s
                GROUP BY 1 ORDER BY 2 DESC, 1
                """.formatted(NCRS, scope.facility(NCR_FACILITY)), params,
                (row, n) -> new Row(row.getString("category"), row.getLong("ncrs")));
        long total = rows.stream().mapToLong(Row::ncrs).sum();
        List<DefectShare> shares = new ArrayList<>();
        long running = 0;
        for (Row row : rows) {
            running += row.ncrs();
            shares.add(new DefectShare(row.category(), row.ncrs(), rate(row.ncrs(), total), rate(running, total)));
        }
        return shares;
    }

    private List<QualityShare> products(Resolved scope, MapSqlParameterSource params) {
        return shares("""
                SELECT p.name, count(*) AS total, count(*) FILTER (WHERE i.final_result = 'PASS') AS passed
                FROM inspections i
                JOIN production_batches b ON b.tenant_id = i.tenant_id AND b.id = i.production_batch_id
                JOIN products p ON p.tenant_id = b.tenant_id AND p.id = b.product_id
                WHERE %s%s
                GROUP BY 1 ORDER BY (count(*) FILTER (WHERE i.final_result = 'PASS'))::numeric / count(*), total DESC, 1 LIMIT %d
                """.formatted(DECIDED, scope.facility("i.facility_id"), TOP), params);
    }

    private List<QualityShare> facilities(Resolved scope, MapSqlParameterSource params) {
        return shares("""
                SELECT f.name, count(*) AS total, count(*) FILTER (WHERE i.final_result = 'PASS') AS passed
                FROM inspections i
                JOIN facilities f ON f.tenant_id = i.tenant_id AND f.id = i.facility_id
                WHERE %s%s
                GROUP BY 1 ORDER BY (count(*) FILTER (WHERE i.final_result = 'PASS'))::numeric / count(*), total DESC, 1 LIMIT %d
                """.formatted(DECIDED, scope.facility("i.facility_id"), TOP), params);
    }

    private List<QualityShare> shares(String sql, MapSqlParameterSource params) {
        return jdbc.query(sql, params, (row, n) -> new QualityShare(row.getString("name"), row.getLong("total"),
                row.getLong("passed"), rate(row.getLong("passed"), row.getLong("total"))));
    }

    private List<AgeBucket> ncrAgeing(Resolved scope, MapSqlParameterSource params) {
        Map<String, Object> ages = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE age <= 7) AS week,
                       count(*) FILTER (WHERE age > 7 AND age <= 30) AS month,
                       count(*) FILTER (WHERE age > 30 AND age <= 90) AS quarter,
                       count(*) FILTER (WHERE age > 90) AS older
                FROM (SELECT :today - (n.created_at AT TIME ZONE CAST(:zone AS text))::date AS age
                      FROM %s WHERE %s%s) a
                """.formatted(NCRS, NCR_OPEN, scope.facility(NCR_FACILITY)), params);
        return List.of(
                new AgeBucket("0 to 7 days", count(ages, "week")),
                new AgeBucket("8 to 30 days", count(ages, "month")),
                new AgeBucket("31 to 90 days", count(ages, "quarter")),
                new AgeBucket("Over 90 days", count(ages, "older")));
    }

    private List<CapaMonth> capaTrend(Resolved scope, MapSqlParameterSource params) {
        Map<String, CapaMonth> found = new HashMap<>();
        jdbc.query("""
                WITH scoped AS (SELECT c.created_at, c.closed_at FROM %s WHERE TRUE%s),
                events AS (
                    SELECT created_at AS at, 1 AS opened, 0 AS closed FROM scoped
                    UNION ALL SELECT closed_at, 0, 1 FROM scoped WHERE closed_at IS NOT NULL)
                SELECT to_char(at AT TIME ZONE CAST(:zone AS text), 'YYYY-MM') AS month,
                       sum(opened) AS opened, sum(closed) AS closed
                FROM events WHERE at >= :start AND at < :end
                GROUP BY 1 ORDER BY 1
                """.formatted(CAPAS, scope.facility(NCR_FACILITY)), params,
                row -> {
                    found.put(row.getString("month"),
                            new CapaMonth(row.getString("month"), row.getLong("opened"), row.getLong("closed")));
                });
        return months(scope).stream().map(month -> found.getOrDefault(month, new CapaMonth(month, 0, 0))).toList();
    }

    private Pending pending(Resolved scope, MapSqlParameterSource params) {
        Long inspections = jdbc.queryForObject("SELECT count(*) FROM inspections i WHERE i.status = 'SUBMITTED'"
                + scope.facility("i.facility_id"), params, Long.class);
        Long ncrs = jdbc.queryForObject("SELECT count(*) FROM " + NCRS + " WHERE n.status = 'PENDING_VERIFICATION'"
                + scope.facility(NCR_FACILITY), params, Long.class);
        Long audits = jdbc.queryForObject("SELECT count(*) FROM audits a WHERE a.status = 'PENDING_CLOSURE'"
                + scope.facility("a.facility_id"), params, Long.class);
        return new Pending(inspections, ncrs, audits);
    }

    private List<RecentActivity> recent(Resolved scope, MapSqlParameterSource params) {
        return jdbc.query("""
                (SELECT 'Inspection' AS kind, i.inspection_number AS reference, '/inspections/' || i.id AS link,
                        i.status, i.created_at AS at
                 FROM inspections i WHERE TRUE%s ORDER BY i.created_at DESC LIMIT 8)
                UNION ALL
                (SELECT 'NCR', n.ncr_number, '/ncrs/' || n.id, n.status, n.created_at
                 FROM %s WHERE TRUE%s ORDER BY n.created_at DESC LIMIT 8)
                ORDER BY at DESC LIMIT 8
                """.formatted(scope.facility("i.facility_id"), NCRS, scope.facility(NCR_FACILITY)), params,
                (row, n) -> new RecentActivity(row.getString("kind"), row.getString("reference"), row.getString("link"),
                        row.getString("status"), row.getObject("at", java.time.OffsetDateTime.class)
                                .atZoneSameInstant(scope.zone()).toLocalDate().toString()));
    }

    /** Every month of the period, so that a quiet month shows as a gap and not as nothing. */
    private static List<String> months(Resolved scope) {
        List<String> months = new ArrayList<>();
        for (YearMonth month = YearMonth.from(scope.from()); !month.isAfter(YearMonth.from(scope.to())); month = month.plusMonths(1)) {
            months.add(month.toString());
        }
        return months;
    }

    private static long count(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private static Double rate(long part, long whole) {
        return whole == 0 ? null : round(100.0 * part / whole);
    }

    private static Double rate(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return null;
        }
        BigDecimal rejected = part == null ? BigDecimal.ZERO : part;
        return rejected.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP).doubleValue();
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
