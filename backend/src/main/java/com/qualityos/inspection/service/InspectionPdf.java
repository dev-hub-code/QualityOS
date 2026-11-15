package com.qualityos.inspection.service;

import com.qualityos.calibration.dto.CalibrationDtos.UsedInstrument;
import com.qualityos.common.report.HtmlPdf;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.inspection.dto.InspectionDtos.DecisionResponse;
import com.qualityos.inspection.dto.InspectionDtos.InspectionDetail;
import com.qualityos.inspection.dto.InspectionDtos.ResultResponse;
import com.qualityos.inspection.dto.TemplateDtos.ParameterSpec;
import com.qualityos.material.dto.MaterialDtos.UnitResponse;
import com.qualityos.material.service.MaterialService;
import com.qualityos.organization.dto.OrganizationResponse;
import com.qualityos.organization.service.OrganizationService;
import com.samskivert.mustache.Template;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One inspection's report as a PDF, built from the specification frozen into it, so that it reads the same in years
 * to come. It matches the printable report page.
 */
@Component
public class InspectionPdf {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    private final InspectionService inspections;
    private final MaterialService materials;
    private final OrganizationService organizations;
    private final TenantAccessService access;
    private final HtmlPdf pdf;
    private final Template template;

    InspectionPdf(InspectionService inspections, MaterialService materials, OrganizationService organizations,
            TenantAccessService access, HtmlPdf pdf) {
        this.inspections = inspections;
        this.materials = materials;
        this.organizations = organizations;
        this.access = access;
        this.pdf = pdf;
        template = HtmlPdf.template("report-templates/inspection.html");
    }

    public record Document(String fileName, byte[] content) {
    }

    @Transactional(readOnly = true)
    public Document render(UUID id) {
        InspectionDetail inspection = inspections.get(id);
        OrganizationResponse organization = organizations.getCurrent();
        DateTimeFormatter when = WHEN.withZone(ZoneId.of(organization.timezone()));
        Map<UUID, String> units = materials.listUnits().stream()
                .collect(Collectors.toMap(UnitResponse::id, UnitResponse::code, (a, b) -> a));
        Map<String, ParameterSpec> parameters = inspection.specification().parameters().stream()
                .collect(Collectors.toMap(ParameterSpec::code, Function.identity(), (a, b) -> a));
        Map<UUID, String> members = access.activeMembers();

        List<Map<String, Object>> results = inspection.results().stream().map(result -> {
            ParameterSpec parameter = parameters.get(result.parameterCode());
            String unit = parameter == null || parameter.uomId() == null ? "" : units.getOrDefault(parameter.uomId(), "");
            return Map.<String, Object>of(
                    "parameter", parameter == null ? result.parameterCode() : parameter.name(),
                    "critical", parameter != null && parameter.criticality() != null
                            && "CRITICAL".equals(parameter.criticality().name()),
                    "requirement", parameter == null ? "" : (requirement(parameter) + " " + unit).trim(),
                    "sample", result.sampleNumber(),
                    "value", value(result),
                    "evaluation", label(result.evaluation()),
                    "remarks", result.remarks() == null ? "" : result.remarks());
        }).toList();
        List<Map<String, Object>> decisions = inspection.decisions().stream().map(decision -> Map.<String, Object>of(
                "decision", label(decision.decision()),
                "note", decision.note() == null ? "" : decision.note(),
                "by", name(decision, members),
                "at", when.format(decision.decidedAt()))).toList();
        List<String> instruments = inspection.instruments().stream()
                .map(InspectionPdf::instrument).toList();

        Map<String, Object> model = new HashMap<>();
        model.put("inspection", inspection);
        model.put("organizationName", organization.name());
        model.put("footerName", HtmlPdf.cssString(organization.name()));
        model.put("generated", DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm z")
                .withZone(ZoneId.of(organization.timezone())).format(Instant.now()));
        model.put("quantity", plain(inspection.quantity()));
        model.put("result", label(inspection.finalResult() != null ? inspection.finalResult()
                : inspection.evaluatedResult() != null ? inspection.evaluatedResult() : "Not yet evaluated"));
        model.put("disposition", inspection.disposition() == null ? "None yet" : label(inspection.disposition()));
        model.put("status", label(inspection.status()));
        model.put("results", results);
        model.put("hasResults", !results.isEmpty());
        model.put("decisions", decisions);
        model.put("hasDecisions", !decisions.isEmpty());
        model.put("instruments", String.join(", ", instruments));
        model.put("hasInstruments", !instruments.isEmpty());
        return new Document(inspection.inspectionNumber() + ".pdf", pdf.render(template.execute(model)));
    }

    /** The same wording as the report page. */
    static String requirement(ParameterSpec parameter) {
        if (parameter.dataType() == null || !"NUMERIC".equals(parameter.dataType().name())) {
            return parameter.expectedText() == null ? "Reviewer decides" : parameter.expectedText();
        }
        if (parameter.minValue() != null && parameter.maxValue() != null) {
            return plain(parameter.minValue()) + " to " + plain(parameter.maxValue());
        }
        if (parameter.nominalValue() != null && parameter.tolerance() != null) {
            return plain(parameter.nominalValue()) + " ± " + plain(parameter.tolerance());
        }
        if (parameter.minValue() != null) {
            return "At least " + plain(parameter.minValue());
        }
        if (parameter.maxValue() != null) {
            return "At most " + plain(parameter.maxValue());
        }
        return parameter.nominalValue() == null ? "Reviewer decides" : plain(parameter.nominalValue());
    }

    private static String value(ResultResponse result) {
        if (result.valueNumeric() != null) {
            return plain(result.valueNumeric());
        }
        if (result.valueBoolean() != null) {
            return result.valueBoolean() ? "Yes" : "No";
        }
        if (result.valueDate() != null) {
            return result.valueDate().toString();
        }
        return result.valueText() == null ? "" : result.valueText();
    }

    private static String name(DecisionResponse decision, Map<UUID, String> members) {
        return decision.decidedBy() == null ? "The system" : members.getOrDefault(decision.decidedBy(), "Former member");
    }

    private static String instrument(UsedInstrument used) {
        return used.instrumentCode() + " – " + used.name();
    }

    private static String label(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String words = value.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}
