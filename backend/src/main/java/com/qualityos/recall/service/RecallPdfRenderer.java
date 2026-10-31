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

    public RecallPdfRenderer(HtmlPdf pdf) {
        this.pdf = pdf;
        template = HtmlPdf.template("report-templates/recall.html");
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
