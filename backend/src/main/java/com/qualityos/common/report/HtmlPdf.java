package com.qualityos.common.report;

import com.qualityos.common.tenant.TenantContext;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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

    /** Where a template wants the organisation's logo. Invoices are ProductionOS's own, and carry none. */
    public static final String LOGO = "<!--organisation-logo-->";

    private final byte[] regular = font("fonts/DejaVuSans.ttf");
    private final byte[] bold = font("fonts/DejaVuSans-Bold.ttf");
    private final JdbcTemplate jdbc;

    /** Without the database, as in rendering samples: no logo. */
    public HtmlPdf() {
        this(null);
    }

    @Autowired
    public HtmlPdf(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The current organisation's logo, embedded in the document, or nothing. Only PNG and JPEG are ever stored, so
     * the type is one the renderer reads and no file is fetched.
     */
    private String logo() {
        UUID tenant = jdbc == null ? null : TenantContext.findCurrentTenantId().orElse(null);
        if (tenant == null) {
            return "";
        }
        return jdbc.query("SELECT content_type, data FROM tenant_logo(?)", (rows, row) ->
                        "<img class=\"organisation-logo\" alt=\"\" src=\"data:" + rows.getString("content_type") + ";base64,"
                                + Base64.getEncoder().encodeToString(rows.getBytes("data")) + "\"/>",
                        tenant).stream().findFirst().orElse("");
    }

    /** A page header or footer is a CSS string, where a quote in a name would end it and drop the whole rule. */
    public static String cssString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static Template template(String path) {
        try (Reader source = new InputStreamReader(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8)) {
            return Mustache.compiler().defaultValue("").compile(source);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public byte[] render(String html) {
        if (html.contains(LOGO)) {
            html = html.replace(LOGO, logo());
        }
        try (ByteArrayOutputStream pdf = new ByteArrayOutputStream()) {
            new PdfRendererBuilder()
                    .useFastMode()
                    .useFont(() -> stream(regular), SANS, 400, FontStyle.NORMAL, true)
                    .useFont(() -> stream(bold), SANS, 700, FontStyle.NORMAL, true)
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
