package com.qualityos.common.report;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Builds a PDF from an HTML template. The fonts are bundled so that it looks the same on every server. */
@Component
public class HtmlPdf {

    private static final String SANS = "Report Sans";
    private static final String DEVANAGARI = "Report Devanagari";

    private final byte[] regular = font("fonts/DejaVuSans.ttf");
    private final byte[] bold = font("fonts/DejaVuSans-Bold.ttf");
    private final byte[] devanagari = font("fonts/NotoSansDevanagari-Regular.ttf");

    public static Template template(String path) {
        try (Reader source = new InputStreamReader(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8)) {
            return Mustache.compiler().defaultValue("").compile(source);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public byte[] render(String html) {
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
