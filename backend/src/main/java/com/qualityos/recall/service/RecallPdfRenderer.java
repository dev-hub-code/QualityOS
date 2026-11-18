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
        model.put("dispatched", plain(report.recall().review().dispatchedQuantity()));
        model.put("returned", plain(report.recall().review().returnedQuantity()));
        model.put("outstanding", plain(report.recall().review().outstandingQuantity()));
        model.put("footerName", HtmlPdf.cssString(report.organizationName()));
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
                "dispatched", plain(report.recall().review().dispatchedQuantity()),
                "returned", plain(report.recall().review().returnedQuantity()),
                "outstanding", plain(report.recall().review().outstandingQuantity())));
        model.put("footerName", HtmlPdf.cssString(report.organizationName()));
        return pdf.render(template.execute(model));
    }

    private static String plain(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }
}
