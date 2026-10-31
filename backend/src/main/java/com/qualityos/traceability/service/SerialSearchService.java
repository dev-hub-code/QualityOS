package com.qualityos.traceability.service;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.response.PageResponse;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.organization.service.OrganizationService;
import com.qualityos.traceability.dto.TraceabilityDtos.SerialFilter;
import com.qualityos.traceability.dto.TraceabilityDtos.SerialSearchResult;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Spans products, batches, dispatches and customers, so it is one query. Row-level security still limits it to the tenant. */
@Service
public class SerialSearchService {

    public static final Set<String> SORTABLE =
            Set.of("serialNumber", "batchNumber", "manufacturedAt", "customerName", "dispatchedOn");
    public static final String DEFAULT_SORT = "serialNumber";

    private static final int MIN_SEARCH = 2;
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "serialNumber", "u.serial_number",
            "batchNumber", "b.batch_number",
            "manufacturedAt", "b.completed_at",
            "customerName", "c.name",
            "dispatchedOn", "d.dispatched_on");
    private static final Map<String, List<String>> FIELDS = Map.of(
            "SERIAL", List.of("u.serial_number"),
            "PRODUCT", List.of("p.code", "p.name"),
            "BATCH", List.of("b.batch_number"),
            "CUSTOMER", List.of("c.name"),
            "DISPATCH", List.of("d.dispatch_number"));
    private static final List<String> ALL_FIELDS = List.of(
            "u.serial_number", "p.code", "p.name", "b.batch_number", "c.name", "d.dispatch_number");

    private static final String FROM = """
            FROM finished_products u
            JOIN production_batches b ON b.tenant_id = u.tenant_id AND b.id = u.production_batch_id
            JOIN products p ON p.tenant_id = b.tenant_id AND p.id = b.product_id
            JOIN facilities f ON f.tenant_id = b.tenant_id AND f.id = b.facility_id
            LEFT JOIN LATERAL (
                SELECT r.dispatch_item_id
                FROM dispatch_reversal_items r
                WHERE r.tenant_id = u.tenant_id AND r.serial_numbers @> to_jsonb(u.serial_number)
                  AND r.status NOT IN ('EXPECTED', 'CANCELLED')
                ORDER BY r.created_at DESC LIMIT 1
            ) back ON true
            LEFT JOIN dispatch_items i ON i.tenant_id = u.tenant_id
                AND i.id = coalesce(u.dispatch_item_id, back.dispatch_item_id)
            LEFT JOIN dispatch_records d ON d.tenant_id = i.tenant_id AND d.id = i.dispatch_id
            LEFT JOIN customers c ON c.tenant_id = d.tenant_id AND c.id = d.customer_id
            LEFT JOIN dispatch_shipments s ON s.tenant_id = d.tenant_id AND s.dispatch_id = d.id
            """;
    private static final String COLUMNS = """
            SELECT u.serial_number, p.code AS product_code, p.name AS product_name, b.batch_number,
                f.name AS facility_name, b.completed_at, b.status AS quality_status, c.name AS customer_name,
                d.dispatch_number, d.dispatched_on,
                CASE WHEN i.id IS NULL THEN 'NOT_DISPATCHED'
                     WHEN i.id = back.dispatch_item_id THEN 'RETURNED'
                     WHEN s.id IS NOT NULL THEN 'SHIPPED'
                     ELSE 'AWAITING_SHIPMENT' END AS dispatch_status,
                (SELECT coalesce(n.final_result, n.status) FROM inspections n
                 WHERE n.tenant_id = b.tenant_id AND n.production_batch_id = b.id
                 ORDER BY n.created_at DESC LIMIT 1) AS inspection_status
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final FacilityScope scope;
    private final OrganizationService organizations;

    SerialSearchService(NamedParameterJdbcTemplate jdbc, FacilityScope scope, OrganizationService organizations) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.organizations = organizations;
    }

    /** @param seesInspections when false, the inspection column stays empty for this caller */
    @Transactional(readOnly = true)
    public PageResponse<SerialSearchResult> search(SerialFilter filter, Pageable page, boolean seesInspections) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = conditions(filter, params);
        Long total = jdbc.queryForObject("SELECT count(*) " + FROM + where, params, Long.class);
        params.addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        List<SerialSearchResult> rows = jdbc.query(
                COLUMNS + FROM + where + order(filter, page, params) + " LIMIT :limit OFFSET :offset", params,
                (row, number) -> new SerialSearchResult(
                        row.getString("serial_number"),
                        row.getString("product_code"),
                        row.getString("product_name"),
                        row.getString("batch_number"),
                        row.getString("facility_name"),
                        Optional.ofNullable(row.getTimestamp("completed_at")).map(java.sql.Timestamp::toInstant).orElse(null),
                        seesInspections ? row.getString("inspection_status") : null,
                        row.getString("quality_status"),
                        row.getString("dispatch_status"),
                        row.getString("customer_name"),
                        row.getString("dispatch_number"),
                        Optional.ofNullable(row.getDate("dispatched_on")).map(Date::toLocalDate).orElse(null)));
        return PageResponse.from(new PageImpl<>(rows, page, total));
    }

    private String conditions(SerialFilter filter, MapSqlParameterSource params) {
        List<String> found = new ArrayList<>();
        String query = filter.query() == null ? "" : filter.query().trim();
        if (!query.isEmpty()) {
            if (query.length() < MIN_SEARCH) {
                throw new BusinessRuleException("Enter at least " + MIN_SEARCH + " characters.");
            }
            params.addValue("pattern", "%" + query.replaceAll("([\\\\%_])", "\\\\$1") + "%");
            found.add("(" + String.join(" OR ", columns(filter.field()).stream()
                    .map(column -> column + " ILIKE :pattern").toList()) + ")");
        }
        add(found, params, "f.id = :facility", "facility", filter.facilityId());
        String zone = organizations.getCurrent().timezone();
        String madeOn = "(b.completed_at AT TIME ZONE :zone)::date";
        add(found, params, madeOn + " >= :madeFrom", "madeFrom", date(filter.madeFrom()));
        add(found, params, madeOn + " <= :madeTo", "madeTo", date(filter.madeTo()));
        add(found, params, "d.dispatched_on >= :dispatchedFrom", "dispatchedFrom", date(filter.dispatchedFrom()));
        add(found, params, "d.dispatched_on <= :dispatchedTo", "dispatchedTo", date(filter.dispatchedTo()));
        // Asked for nothing in particular, this would page through every unit the tenant ever made.
        if (found.isEmpty()) {
            throw new BusinessRuleException("Enter something to search for, or choose a facility or dates.");
        }
        params.addValue("zone", zone);
        scope.restriction().ifPresent(allowed -> {
            found.add("f.id IN (:allowed)");
            params.addValue("allowed", allowed);
        });
        return "WHERE " + String.join(" AND ", found) + " ";
    }

    private static List<String> columns(String field) {
        if (field == null || field.isBlank()) {
            return ALL_FIELDS;
        }
        List<String> columns = FIELDS.get(field.toUpperCase(Locale.ROOT));
        if (columns == null) {
            throw new BusinessRuleException("Cannot search by " + field + ".");
        }
        return columns;
    }

    private static void add(List<String> found, MapSqlParameterSource params, String sql, String name, Object value) {
        if (value != null) {
            found.add(sql);
            params.addValue(name, value);
        }
    }

    private static Date date(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }

    /** An exact serial number comes first, however the rest are sorted. */
    private static String order(SerialFilter filter, Pageable page, MapSqlParameterSource params) {
        Sort.Order sort = page.getSort().iterator().next();
        String column = SORT_COLUMNS.get(sort.getProperty());
        String direction = (sort.isAscending() ? " ASC" : " DESC") + " NULLS LAST, u.serial_number";
        if (filter.query() == null || filter.query().isBlank()) {
            return "ORDER BY " + column + direction;
        }
        params.addValue("exact", filter.query().trim());
        return "ORDER BY (lower(u.serial_number) = lower(:exact)) DESC, " + column + direction;
    }
}
