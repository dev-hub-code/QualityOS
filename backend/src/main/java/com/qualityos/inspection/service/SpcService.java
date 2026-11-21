package com.qualityos.inspection.service;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.inspection.dto.SpcDtos.ChartType;
import com.qualityos.inspection.dto.SpcDtos.Characteristic;
import com.qualityos.inspection.dto.SpcDtos.SpcChart;
import com.qualityos.inspection.service.SpcCalculator.Result;
import com.qualityos.inspection.service.SpcCalculator.Spec;
import com.qualityos.inspection.service.SpcCalculator.Subgroup;
import com.qualityos.organization.service.OrganizationService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Charts a template's numeric parameter from the inspections that measured it: submitted, approved or completed ones
 * at the facilities the member can open. Each inspection is judged by the specification it was carried out to.
 */
@Service
public class SpcService {

    static final int DEFAULT_DAYS = 90;
    static final int MAX_DAYS = 731;
    /** The latest this many inspections are charted. */
    static final int MAX_SUBGROUPS = 200;

    /** Inspections whose measurements are final enough to chart. */
    private static final String MEASURED = "i.status IN ('SUBMITTED', 'APPROVED', 'COMPLETED')";
    /** The parameter as the inspection's own specification has it. */
    private static final String SPEC = """
            jsonb_path_query_first(i.spec_snapshot, '$.parameters[*] ? (@.code == $code)',
                jsonb_build_object('code', r.parameter_code))""";

    private final JdbcTemplate jdbc;
    private final FacilityScope scope;
    private final OrganizationService organizations;

    SpcService(JdbcTemplate jdbc, FacilityScope scope, OrganizationService organizations) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.organizations = organizations;
    }

    /** Numeric parameters measured in the last year, by template and code. */
    @Transactional(readOnly = true)
    public List<Characteristic> characteristics() {
        UUID[] allowed = allowedFacilities();
        return jdbc.query("""
                SELECT t.id, t.code, t.name, t.inspection_type, r.parameter_code,
                       (array_agg(p->>'name' ORDER BY r.measured_at DESC))[1] AS parameter_name,
                       count(*) AS measurements, max(r.measured_at) AS last_measured_at
                FROM inspection_results r
                JOIN inspections i ON i.tenant_id = r.tenant_id AND i.id = r.inspection_id
                JOIN inspection_template_versions v ON v.tenant_id = i.tenant_id AND v.id = i.template_version_id
                JOIN inspection_templates t ON t.tenant_id = v.tenant_id AND t.id = v.template_id
                CROSS JOIN LATERAL %s AS p
                WHERE r.value_numeric IS NOT NULL AND %s AND r.measured_at >= now() - interval '1 year'
                  AND (?::uuid[] IS NULL OR i.facility_id = ANY (?::uuid[]))
                GROUP BY t.id, t.code, t.name, t.inspection_type, r.parameter_code
                ORDER BY t.name, r.parameter_code
                """.formatted(SPEC, MEASURED), (rows, row) -> new Characteristic(rows.getObject("id", UUID.class),
                rows.getString("code"), rows.getString("name"), rows.getString("inspection_type"),
                rows.getString("parameter_code"), rows.getString("parameter_name"), rows.getLong("measurements"),
                rows.getTimestamp("last_measured_at").toInstant()), allowed, allowed);
    }

    /**
     * A control chart of the parameter between the two days, both included and in the organisation's time zone: the
     * last 90 days when none are given. Without a chart type, the one the samples suit. {@code facilityId} narrows it
     * to one facility.
     */
    @Transactional(readOnly = true)
    public SpcChart chart(UUID templateId, String parameterCode, ChartType type, LocalDate from, LocalDate to,
            UUID facilityId) {
        ZoneId zone = ZoneId.of(organizations.getCurrent().timezone());
        LocalDate end = to != null ? to : LocalDate.now(zone);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1);
        if (end.isBefore(start)) {
            throw new BusinessRuleException("The period ends before it starts.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            throw new BusinessRuleException("Choose a period of at most %d days.".formatted(MAX_DAYS));
        }
        if (facilityId != null) {
            scope.requireAllowed(facilityId, "Facility", facilityId.toString());
        }
        Template template = template(templateId);
        UUID[] allowed = facilityId != null ? new UUID[] {facilityId} : allowedFacilities();
        List<Reading> readings = jdbc.query("""
                SELECT i.id, i.inspection_number, i.batch_number, r.sample_number, r.value_numeric, r.measured_at,
                       p->>'name' AS name, p->>'uomId' AS uom_id, p->>'minValue' AS min_value,
                       p->>'maxValue' AS max_value, p->>'nominalValue' AS nominal_value, p->>'tolerance' AS tolerance
                FROM inspection_results r
                JOIN inspections i ON i.tenant_id = r.tenant_id AND i.id = r.inspection_id
                JOIN inspection_template_versions v ON v.tenant_id = i.tenant_id AND v.id = i.template_version_id
                CROSS JOIN LATERAL %s AS p
                WHERE v.template_id = ? AND r.parameter_code = ? AND r.value_numeric IS NOT NULL AND %s
                  AND r.measured_at >= ? AND r.measured_at < ?
                  AND (?::uuid[] IS NULL OR i.facility_id = ANY (?::uuid[]))
                  AND p->>'dataType' = 'NUMERIC'
                ORDER BY r.measured_at, i.id, r.sample_number
                """.formatted(SPEC, MEASURED), SpcService::reading, templateId, parameterCode,
                java.sql.Timestamp.from(start.atStartOfDay(zone).toInstant()),
                java.sql.Timestamp.from(end.plusDays(1).atStartOfDay(zone).toInstant()), allowed, allowed);
        if (readings.isEmpty()) {
            throw new BusinessRuleException("No measurements of %s in this period.".formatted(parameterCode));
        }

        // One subgroup per inspection, in the order its first measurement was taken; the latest ones only.
        Map<UUID, List<Reading>> byInspection = new LinkedHashMap<>();
        readings.forEach(reading -> byInspection.computeIfAbsent(reading.inspectionId(), ignored -> new ArrayList<>())
                .add(reading));
        List<Subgroup> subgroups = new ArrayList<>();
        byInspection.values().forEach(theirs -> {
            Reading first = theirs.getFirst();
            subgroups.add(new Subgroup(first.inspectionId(), first.inspectionNumber(), first.batchNumber(),
                    first.measuredAt(), theirs.stream().sorted(java.util.Comparator.comparingInt(Reading::sample))
                    .map(Reading::value).toList()));
        });
        subgroups.sort(java.util.Comparator.comparing(Subgroup::measuredAt));
        boolean truncated = subgroups.size() > MAX_SUBGROUPS;
        List<Subgroup> shown = truncated ? subgroups.subList(subgroups.size() - MAX_SUBGROUPS, subgroups.size())
                : subgroups;

        // The latest inspection's specification is the one judged against.
        Set<UUID> shownIds = new java.util.HashSet<>(shown.stream().map(Subgroup::inspectionId).toList());
        List<Reading> inView = readings.stream().filter(reading -> shownIds.contains(reading.inspectionId())).toList();
        Reading latest = inView.stream().max(java.util.Comparator.comparing(Reading::measuredAt)).orElseThrow();
        boolean specChanged = inView.stream().map(Reading::limits).distinct().count() > 1;
        ChartType chosen = type != null ? type : SpcCalculator.suitedTo(shown);
        Result result = SpcCalculator.calculate(chosen, shown, latest.limits().spec());
        return new SpcChart(templateId, template.code(), template.name(), parameterCode, latest.name(),
                unit(latest.uomId()), result.chartType(), start, end, result.subgroupSize(),
                result.excludedSubgroups(), latest.limits().spec().lower(), latest.limits().spec().upper(),
                latest.limits().nominal(), specChanged, result.valueLimits(), result.rangeLimits(), result.points(),
                result.capability(), result.preliminary(), truncated);
    }

    private record Template(String code, String name) {
    }

    /** A specification's limits, worked out as the evaluation does: min and max, else nominal ± tolerance. */
    private record SpecLimits(Double lower, Double upper, Double nominal) {

        Spec spec() {
            return new Spec(lower, upper);
        }

        static SpecLimits of(BigDecimal min, BigDecimal max, BigDecimal nominal, BigDecimal tolerance) {
            BigDecimal low = min;
            BigDecimal high = max;
            if (nominal != null && tolerance != null) {
                low = low != null ? low : nominal.subtract(tolerance);
                high = high != null ? high : nominal.add(tolerance);
            }
            return new SpecLimits(low == null ? null : low.doubleValue(), high == null ? null : high.doubleValue(),
                    nominal == null ? null : nominal.doubleValue());
        }
    }

    private record Reading(UUID inspectionId, String inspectionNumber, String batchNumber, int sample, double value,
            Instant measuredAt, String name, String uomId, SpecLimits limits) {
    }

    private static Reading reading(ResultSet rows, int row) throws SQLException {
        return new Reading(rows.getObject("id", UUID.class), rows.getString("inspection_number"),
                rows.getString("batch_number"), rows.getInt("sample_number"),
                rows.getBigDecimal("value_numeric").doubleValue(), rows.getTimestamp("measured_at").toInstant(),
                rows.getString("name"), rows.getString("uom_id"), SpecLimits.of(decimal(rows, "min_value"),
                        decimal(rows, "max_value"), decimal(rows, "nominal_value"), decimal(rows, "tolerance")));
    }

    private static BigDecimal decimal(ResultSet rows, String column) throws SQLException {
        String text = rows.getString(column);
        return text == null || text.isBlank() ? null : new BigDecimal(text);
    }

    private Template template(UUID templateId) {
        List<Template> found = jdbc.query("SELECT code, name FROM inspection_templates WHERE id = ?",
                (rows, row) -> new Template(rows.getString("code"), rows.getString("name")), templateId);
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("Inspection template", templateId);
        }
        return found.getFirst();
    }

    private String unit(String uomId) {
        if (uomId == null || uomId.isBlank()) {
            return null;
        }
        return jdbc.query("SELECT code FROM units_of_measure WHERE id = ?::uuid", (rows, row) -> rows.getString(1),
                uomId).stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    /** Null when the member is not limited, as an empty array would match nothing. */
    private UUID[] allowedFacilities() {
        return scope.restriction().map(ids -> ids.toArray(UUID[]::new)).orElse(null);
    }
}
