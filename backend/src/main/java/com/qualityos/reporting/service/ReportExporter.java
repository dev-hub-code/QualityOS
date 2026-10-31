package com.qualityos.reporting.service;

import com.qualityos.common.report.HtmlPdf;
import com.qualityos.reporting.dto.ReportingDtos.Column;
import com.qualityos.reporting.dto.ReportingDtos.ReportTable;
import com.samskivert.mustache.Template;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.springframework.stereotype.Component;

@Component
public class ReportExporter {

    private static final int HEADER_ROW = 4;
    private static final double WIDTH_PER_WEIGHT = 1.4;

    private final HtmlPdf pdf;
    private final Template template;

    public ReportExporter(HtmlPdf pdf) {
        this.pdf = pdf;
        template = HtmlPdf.template("report-templates/report.html");
    }

    public byte[] pdf(ReportTable report) {
        int total = report.columns().stream().mapToInt(Column::weight).sum();
        List<Map<String, Object>> headings = report.columns().stream()
                .map(column -> Map.<String, Object>of(
                        "label", column.label(),
                        "width", 100.0 * column.weight() / total,
                        "align", column.numeric() ? "num" : ""))
                .toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<String> values : report.rows()) {
            List<Map<String, Object>> cells = new ArrayList<>();
            for (int index = 0; index < values.size(); index++) {
                cells.add(Map.of(
                        "value", values.get(index) == null ? "" : values.get(index).replace('_', ' '),
                        "align", report.columns().get(index).numeric() ? "num" : ""));
            }
            rows.add(Map.of("cells", cells));
        }
        return pdf.render(template.execute(Map.of(
                "report", report, "footerName", HtmlPdf.cssString(report.organizationName()), "headings", headings, "rows", rows, "rowCount", rows.size(),
                "empty", rows.isEmpty())));
    }

    /** Text goes in as text, so that a value beginning with an equals sign is never run as a formula. */
    public byte[] excel(ReportTable report) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Workbook workbook = new Workbook(out, "QualityOS", "1.0");
            Worksheet sheet = workbook.newWorksheet("Report");
            sheet.value(0, 0, report.title());
            sheet.style(0, 0).bold().fontSize(14).set();
            sheet.value(1, 0, report.organizationName() + ", " + report.facilityLabel());
            sheet.value(2, 0, report.periodLabel() + ". Generated " + report.generatedLabel());
            List<Column> columns = report.columns();
            for (int index = 0; index < columns.size(); index++) {
                sheet.value(HEADER_ROW, index, columns.get(index).label());
                sheet.style(HEADER_ROW, index).bold().fillColor("E8E8E8").set();
                sheet.width(index, columns.get(index).weight() * WIDTH_PER_WEIGHT);
            }
            sheet.freezePane(0, HEADER_ROW + 1);
            int row = HEADER_ROW + 1;
            for (List<String> values : report.rows()) {
                for (int index = 0; index < values.size(); index++) {
                    String value = values.get(index);
                    if (value == null) {
                        continue;
                    }
                    if (columns.get(index).numeric()) {
                        sheet.value(row, index, new BigDecimal(value));
                    } else {
                        sheet.value(row, index, value);
                    }
                }
                row++;
            }
            if (report.truncated()) {
                sheet.value(row + 1, 0, "This report stops here. Choose fewer dates to see the rest.");
            }
            workbook.finish();
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
