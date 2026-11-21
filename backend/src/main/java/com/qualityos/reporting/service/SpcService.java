package com.qualityos.reporting.service;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.exception.QualityOsException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.reporting.dto.SpcDtos.ChartChoice;
import com.qualityos.reporting.dto.SpcDtos.ChartType;
import com.qualityos.reporting.dto.SpcDtos.ParameterSpec;
import com.qualityos.reporting.dto.SpcDtos.SpcChart;
import com.qualityos.reporting.dto.SpcDtos.SpcParameter;
import com.qualityos.reporting.service.ReportScope.Resolved;
import com.qualityos.reporting.service.SpcMath.Reading;
import com.qualityos.reporting.service.SpcMath.Result;
import com.qualityos.reporting.service.SpcMath.Spec;
import com.qualityos.reporting.service.SpcMath.Subgroup;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Control charts of a template's numeric parameter, from the inspections whose results have been submitted. A point's
 * time is its inspection's submission; each inspection carries the specification it was carried out to, and the
 * latest one is the one capability is judged against. Nothing is stored.
 */
@Service
public class SpcService {

    static final int MAX_SUBGROUPS = 500;
    static final int MAX_READINGS = 1000;

    private static final String SUBMITTED = "i.status IN ('SUBMITTED', 'APPROVED', 'COMPLETED')";
    /** The parameter as the inspection's frozen specification has it. */
    private static final String SPEC = """
            jsonb_path_query_first(i.spec_snapshot, '$.parameters[*] ? (@.code == $code)',
                jsonb_build_object('code', r.parameter_code))""";
    private static final String FROM = """
            FROM inspection_results r
            JOIN inspections i ON i.tenant_id = r.tenant_id AND i.id = r.inspection_id
            JOIN inspection_template_versions v ON v.tenant_id = i.tenant_id AND v.id = i.template_version_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ReportScope scopes;

    SpcService(NamedParameterJdbcTemplate jdbc, ReportScope scopes) {
        this.jdbc = jdbc;
        this.scopes = scopes;
    }

    /** Numeric parameters with readings in the period, the most recently submitted first. */
    @Transactional(readOnly = true)
    public List<SpcParameter> parameters(LocalDate from, LocalDate to, UUID facilityId) {
        Resolved scope = scopes.resolve(from, to, facilityId);
        return jdbc.query(("""
                SELECT t.id, t.code, t.name, r.parameter_code,
                       (array_agg(p->>'name' ORDER BY i.submitted_at DESC))[1] AS parameter_name,
                       count(DISTINCT i.id) AS subgroups, max(i.submitted_at) AS last_submitted_at
                """ + FROM + """
                JOIN inspection_templates t ON t.tenant_id = v.tenant_id AND t.id = v.template_id
                CROSS JOIN LATERAL %s AS p
                WHERE r.value_numeric IS NOT NULL AND %s AND p->>'dataType' = 'NUMERIC'
                  AND i.submitted_at >= :start AND i.submitted_at < :end %s
                GROUP BY t.id, t.code, t.name, r.parameter_code
                ORDER BY last_submitted_at DESC, t.name, r.parameter_code
                """).formatted(SPEC, SUBMITTED, scope.facility("i.facility_id")), scope.params(),
                (rows, row) -> new SpcParameter(rows.getObject("id", UUID.class), rows.getString("code"),
                        rows.getString("name"), rows.getString("parameter_code"), rows.getString("parameter_name"),
                        rows.getLong("subgroups"), rows.getTimestamp("last_submitted_at").toInstant()));
    }

    /**
     * One parameter's chart over the period, at the member's facilities or the one chosen, and for one material or
     * product if given. AUTO picks X̄-R when at least half the inspections took two readings or more.
     */
    @Transactional(readOnly = true)
    public SpcChart chart(UUID templateId, String parameterCode, ChartChoice choice, LocalDate from, LocalDate to,
            UUID facilityId, UUID materialId, UUID productId) {
        if (materialId != null && productId != null) {
            throw new QualityOsException(ErrorCode.VALIDATION_FAILED, "Choose a material or a product, not both.");
        }
        Resolved scope = scopes.resolve(from, to, facilityId);
        Template template = template(templateId);
        requireNumeric(templateId, parameterCode);

        MapSqlParameterSource params = scope.params().addValue("template", templateId)
                .addValue("code", parameterCode).addValue("material", materialId).addValue("product", productId);
        String item = materialId != null ? " AND i.material_id = :material"
                : productId != null ? """
                         AND EXISTS (SELECT 1 FROM production_batches b WHERE b.tenant_id = i.tenant_id
                                     AND b.id = i.production_batch_id AND b.product_id = :product)"""
                : "";
        List<Row> rows = jdbc.query(("""
                SELECT i.id, i.inspection_number, i.batch_number, i.submitted_at, r.sample_number, r.value_numeric,
                       p->>'name' AS name, p->>'uomId' AS uom_id, p->>'minValue' AS min_value,
                       p->>'maxValue' AS max_value, p->>'nominalValue' AS nominal_value, p->>'tolerance' AS tolerance
                """ + FROM + """
                CROSS JOIN LATERAL %s AS p
                WHERE v.template_id = :template AND r.parameter_code = :code AND r.value_numeric IS NOT NULL AND %s
                  AND i.submitted_at >= :start AND i.submitted_at < :end %s %s
                ORDER BY i.submitted_at, i.id, r.sample_number
                """).formatted(SPEC, SUBMITTED, scope.facility("i.facility_id"), item), params, SpcService::row);
        if (rows.isEmpty()) {
            throw new BusinessRuleException("There are no readings of %s in this period.".formatted(parameterCode));
        }

        // One subgroup per inspection, in order of submission.
        Map<UUID, List<Row>> byInspection = new LinkedHashMap<>();
        rows.forEach(row -> byInspection.computeIfAbsent(row.inspectionId(), ignored -> new ArrayList<>()).add(row));
        List<Subgroup> subgroups = new ArrayList<>();
        Map<UUID, Row> firstRows = new LinkedHashMap<>();
        byInspection.forEach((id, theirs) -> {
            Row first = theirs.getFirst();
            firstRows.put(id, first);
            subgroups.add(new Subgroup(id, first.inspectionNumber(), first.batchNumber(), first.submittedAt(),
                    theirs.stream().map(row -> new Reading(row.sample(), row.value())).toList()));
        });

        ChartType type = switch (choice == null ? ChartChoice.AUTO : choice) {
            case AUTO -> SpcMath.auto(subgroups);
            case XBAR_R -> ChartType.XBAR_R;
            case I_MR -> ChartType.I_MR;
        };
        List<Subgroup> charted = type == ChartType.XBAR_R ? latestSubgroups(subgroups) : latestReadings(subgroups);
        boolean truncated = charted.stream().mapToInt(Subgroup::n).sum() < rows.size();

        // The latest inspection's specification; a change within the charted ones is flagged.
        Limits latest = firstRows.get(charted.getLast().inspectionId()).limits();
        boolean specChanged = charted.stream().map(subgroup -> firstRows.get(subgroup.inspectionId()).limits())
                .distinct().count() > 1;
        Spec spec = new Spec(latest.lsl(), latest.usl());
        Result result = type == ChartType.XBAR_R ? SpcMath.xbarR(charted, spec) : SpcMath.individuals(charted, spec);
        List<String> warnings = new ArrayList<>(result.warnings());
        if (specChanged) {
            warnings.add("The specification changed during this period: Cp and Cpk use the latest limits.");
        }
        Row named = firstRows.get(charted.getLast().inspectionId());
        ParameterSpec parameter = new ParameterSpec(parameterCode, named.name(), unit(named.uomId()), latest.lsl(),
                latest.usl(), latest.nominal(), specChanged);
        return new SpcChart(templateId, template.code(), template.name(), scope.from(), scope.to(),
                result.chartType(), parameter, result.centre(), result.sigma(), result.capability(), result.points(),
                result.excluded(), truncated, List.copyOf(warnings));
    }

    private static List<Subgroup> latestSubgroups(List<Subgroup> subgroups) {
        return subgroups.size() <= MAX_SUBGROUPS ? subgroups
                : subgroups.subList(subgroups.size() - MAX_SUBGROUPS, subgroups.size());
    }

    /** The latest {@link #MAX_READINGS} readings, keeping the subgroups they belong to. */
    private static List<Subgroup> latestReadings(List<Subgroup> subgroups) {
        List<Subgroup> kept = new ArrayList<>();
        int room = MAX_READINGS;
        for (int i = subgroups.size() - 1; i >= 0 && room > 0; i--) {
            Subgroup subgroup = subgroups.get(i);
            List<Reading> readings = subgroup.readings();
            if (readings.size() > room) {
                readings = readings.subList(readings.size() - room, readings.size());
                subgroup = new Subgroup(subgroup.inspectionId(), subgroup.inspectionNumber(), subgroup.batchNumber(),
                        subgroup.submittedAt(), readings);
            }
            kept.addFirst(subgroup);
            room -= readings.size();
        }
        return kept;
    }

    private record Template(String code, String name) {
    }

    /** Limits as the evaluation works them out: min and max, otherwise nominal ∓ tolerance. */
    private record Limits(Double lsl, Double usl, Double nominal) {

        static Limits of(BigDecimal min, BigDecimal max, BigDecimal nominal, BigDecimal tolerance) {
            BigDecimal low = min;
            BigDecimal high = max;
            if (nominal != null && tolerance != null) {
                low = low != null ? low : nominal.subtract(tolerance);
                high = high != null ? high : nominal.add(tolerance);
            }
            return new Limits(low == null ? null : low.doubleValue(), high == null ? null : high.doubleValue(),
                    nominal == null ? null : nominal.doubleValue());
        }
    }

    private record Row(UUID inspectionId, String inspectionNumber, String batchNumber, Instant submittedAt,
            int sample, double value, String name, String uomId, Limits limits) {
    }

    private static Row row(ResultSet rows, int row) throws SQLException {
        return new Row(rows.getObject("id", UUID.class), rows.getString("inspection_number"),
                rows.getString("batch_number"), rows.getTimestamp("submitted_at").toInstant(),
                rows.getInt("sample_number"), rows.getBigDecimal("value_numeric").doubleValue(),
                rows.getString("name"), rows.getString("uom_id"), Limits.of(decimal(rows, "min_value"),
                        decimal(rows, "max_value"), decimal(rows, "nominal_value"), decimal(rows, "tolerance")));
    }

    private static BigDecimal decimal(ResultSet rows, String column) throws SQLException {
        String text = rows.getString(column);
        return text == null || text.isBlank() ? null : new BigDecimal(text);
    }

    /** Another organisation's template is not found, as row-level security hides it. */
    private Template template(UUID templateId) {
        List<Template> found = jdbc.query("SELECT code, name FROM inspection_templates WHERE id = :id",
                Map.of("id", templateId), (rows, row) -> new Template(rows.getString("code"), rows.getString("name")));
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("Inspection template", templateId);
        }
        return found.getFirst();
    }

    /** The parameter as the template's latest version defines it: it must exist and be numeric. */
    private void requireNumeric(UUID templateId, String parameterCode) {
        List<String> types = jdbc.queryForList("""
                SELECT p.data_type FROM inspection_parameters p
                JOIN inspection_template_versions v ON v.tenant_id = p.tenant_id AND v.id = p.template_version_id
                WHERE v.template_id = :template AND p.code = :code
                ORDER BY v.version_number DESC LIMIT 1
                """, Map.of("template", templateId, "code", parameterCode), String.class);
        if (types.isEmpty()) {
            throw new BusinessRuleException("The template has no parameter %s.".formatted(parameterCode));
        }
        if (!"NUMERIC".equals(types.getFirst())) {
            throw new BusinessRuleException("%s is a %s parameter: only numeric parameters can be charted."
                    .formatted(parameterCode, types.getFirst()));
        }
    }

    private String unit(String uomId) {
        if (uomId == null || uomId.isBlank()) {
            return null;
        }
        return jdbc.queryForList("SELECT code FROM units_of_measure WHERE id = CAST(:id AS uuid)", Map.of("id", uomId),
                String.class).stream().filter(Objects::nonNull).findFirst().orElse(null);
    }
}
