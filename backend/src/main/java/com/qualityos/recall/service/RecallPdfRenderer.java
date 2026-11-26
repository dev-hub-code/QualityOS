package com.qualityos.recall.service;

import com.qualityos.common.report.HtmlPdf;
import com.qualityos.recall.dto.RecallDtos.RecallReport;
import com.samskivert.mustache.Template;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RecallPdfRenderer {

    private final HtmlPdf pdf;
    private final Template template;
    private final Template regulatory;

    public RecallPdfRenderer(HtmlPdf pdf) {
        this.pdf = pdf;
        template = HtmlPdf.template("report-templates/recall.html");
        regulatory = HtmlPdf.template("report-templates/recall-regulatory.html");
    }

    public byte[] renderRegulatory(com.qualityos.recall.dto.RecallDtos.RegulatoryReport pack) {
        RecallReport report = pack.report();
        Map<String, Object> model = new HashMap<>();
        model.put("pack", pack);
        model.put("report", report);
        model.put("kind", pack.finalReport() ? "Final" : "Interim");
        model.put("hazard", report.recall().hazardClass() == null ? "Not yet classified"
                : switch (report.recall().hazardClass()) {
                    case "CLASS_I" -> "Class I: may cause serious harm or death";
                    case "CLASS_II" -> "Class II: may cause temporary or reversible harm";
                    default -> "Class III: unlikely to cause harm";
                });
        model.put("hasUnanswered", !pack.unanswered().isEmpty());
        model.put("dispatched", report.recall().review().totals().dispatched());
        model.put("returned", report.recall().review().totals().returned());
        model.put("outstanding", report.recall().review().totals().outstanding());
        model.put("footerName", HtmlPdf.cssString(report.organizationName()));
        addFormatters(model);
        return pdf.render(regulatory.execute(model));
    }

    public byte[] render(RecallReport report) {
        Map<String, Object> model = new HashMap<>(Map.of(
                "report", report,
                "hasInspections", !report.inspections().isEmpty(),
                "hasNcrs", !report.ncrs().isEmpty(),
                "hasHistory", !report.history().isEmpty(),
                "hasSerials", !report.serialNumbers().isEmpty(),
                "serials", String.join(",  ", report.serialNumbers()),
                "serialCount", report.serialNumbers().size(),
                "dispatched", report.recall().review().totals().dispatched(),
                "returned", report.recall().review().totals().returned(),
                "outstanding", report.recall().review().totals().outstanding()));
        model.put("footerName", HtmlPdf.cssString(report.organizationName()));
        addFormatters(model);
        return pdf.render(template.execute(model));
    }

    private static final java.time.format.DateTimeFormatter DAY =
            java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu", java.util.Locale.ENGLISH);

    /** {{#words}}CLOSED{{/words}} reads "Closed"; {{#day}}2026-11-26{{/day}} reads "26 Nov 2026". */
    private static void addFormatters(Map<String, Object> model) {
        model.put("words", (com.samskivert.mustache.Mustache.Lambda) (fragment, out) ->
                out.write(com.qualityos.common.util.EnumLabels.label(fragment.execute().trim())));
        model.put("day", (com.samskivert.mustache.Mustache.Lambda) (fragment, out) -> {
            String text = fragment.execute().trim();
            try {
                out.write(java.time.LocalDate.parse(text).format(DAY));
            } catch (java.time.format.DateTimeParseException notADay) {
                out.write(text);
            }
        });
    }

    private static String plain(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }
}
