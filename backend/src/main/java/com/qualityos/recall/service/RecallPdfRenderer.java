package com.qualityos.recall.service;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.qualityos.recall.dto.RecallDtos.RecallReport;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Builds the PDF from an HTML template. The font is bundled so that it looks the same on every server. */
@Component
public class RecallPdfRenderer {

    private static final String SANS = "Report Sans";
    private static final String DEVANAGARI = "Report Devanagari";

    private final Template template;
    private final byte[] regular = font("fonts/DejaVuSans.ttf");
    private final byte[] bold = font("fonts/DejaVuSans-Bold.ttf");
    private final byte[] devanagari = font("fonts/NotoSansDevanagari-Regular.ttf");

    public RecallPdfRenderer() {
        try (Reader source = new InputStreamReader(
                new ClassPathResource("report-templates/recall.html").getInputStream(), StandardCharsets.UTF_8)) {
            template = Mustache.compiler().defaultValue("").compile(source);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public byte[] render(RecallReport report) {
        String html = template.execute(Map.of(
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
        try (ByteArrayOutputStream pdf = new ByteArrayOutputStream()) {
            new PdfRendererBuilder()
                    .useFastMode()
                    .useFont(() -> stream(regular), SANS, 400, FontStyle.NORMAL, true)
                    .useFont(() -> stream(bold), SANS, 700, FontStyle.NORMAL, true)
                    .useFont(() -> stream(devanagari), DEVANAGARI, 400, FontStyle.NORMAL, true)
                    .withHtmlContent(html, null)
                    .toStream(pdf)
                    .run();
            return pdf.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String plain(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }

    private static InputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    private static byte[] font(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
