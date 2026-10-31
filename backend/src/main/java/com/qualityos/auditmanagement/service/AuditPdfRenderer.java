package com.qualityos.auditmanagement.service;

import com.qualityos.auditmanagement.dto.AuditDtos.AuditReport;
import com.qualityos.common.report.HtmlPdf;
import com.samskivert.mustache.Template;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AuditPdfRenderer {

    private final HtmlPdf pdf;
    private final Template template;

    public AuditPdfRenderer(HtmlPdf pdf) {
        this.pdf = pdf;
        template = HtmlPdf.template("report-templates/audit.html");
    }

    public byte[] render(AuditReport report) {
        Map<String, Object> model = new HashMap<>(Map.of(
                "report", report, "hasFindings", !report.audit().findings().isEmpty()));
        model.put("footerName", HtmlPdf.cssString(report.organizationName()));
        return pdf.render(template.execute(model));
    }
}
