package com.qualityos;

import static com.qualityos.reporting.dto.ReportingDtos.Column.number;
import static com.qualityos.reporting.dto.ReportingDtos.Column.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.qualityos.common.report.HtmlPdf;
import com.qualityos.reporting.dto.ReportingDtos.Column;
import com.qualityos.reporting.dto.ReportingDtos.ReportTable;
import com.qualityos.reporting.service.ReportExporter;
import com.qualityos.subscription.entity.Subscription;
import com.qualityos.subscription.entity.SubscriptionInvoice;
import com.qualityos.subscription.service.InvoicePdfRenderer;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Writes sample exports to target/report-samples, to be looked at. A test can only prove that they render. */
class ReportSamplesTest {

    private static final Path OUT = Path.of("target/report-samples");
    private static final List<Column> COLUMNS = List.of(text("NCR", 13), text("Title", 29), text("Severity", 8),
            text("Status", 13), text("Source", 9), text("Facility", 12), text("Raised", 9), text("Closed", 9),
            number("Age (days)", 6));

    private final ReportExporter exporter = new ReportExporter(new HtmlPdf());

    private static ReportTable table(int rows, boolean truncated) {
        return new ReportTable("NCR report", "Acme \"Forgings\" & Sons'", "All facilities you can see", "2026-08-03 to 2026-10-31",
                "31 Oct 2026, 12:00 IST", "NCRs raised in the period.", COLUMNS,
                IntStream.rangeClosed(1, rows)
                        .mapToObj(n -> List.of("NCR-2026-" + String.format("%05d", n),
                                "Audit AUD-2026-00004: Caliper VC-9 was in use without a calibration label",
                                "MAJOR", "PENDING_VERIFICATION", "INSPECTION", "Pune Plant", "2026-10-02", "", "29"))
                        .toList(),
                truncated);
    }

    @Test
    void aQuoteInANameCannotEndAFooter() {
        assertThat(HtmlPdf.cssString("Acme \"Forgings\" \\ Sons")).isEqualTo("Acme \\\"Forgings\\\" \\\\ Sons");
    }

    @Test
    void writesSamples() throws Exception {
        Files.createDirectories(OUT);
        Files.write(OUT.resolve("1-empty.pdf"), exporter.pdf(table(0, false)));
        Files.write(OUT.resolve("2-many-rows.pdf"), exporter.pdf(table(120, true)));
        byte[] excel = exporter.excel(table(120, true));
        Files.write(OUT.resolve("3-many-rows.xlsx"), excel);
        assertThat(excel).isNotEmpty();
    }

    @Test
    void writesAnInvoiceSample() throws Exception {
        SubscriptionInvoice invoice = new SubscriptionInvoice(UUID.randomUUID(), "BILL-2026-00001");
        invoice.setPlanName("Growth");
        invoice.setBillingCycle(Subscription.Cycle.ANNUAL);
        invoice.setAmount(new BigDecimal("59990.00"));
        invoice.setTaxPercent(new BigDecimal("18.00"));
        invoice.setTaxAmount(new BigDecimal("10798.20"));
        invoice.setTotal(new BigDecimal("70788.20"));
        invoice.setBuyerName("Acme \"Forgings\" & Sons");
        invoice.setBuyerGstin("27AAPFU0939F1ZV");
        invoice.setBuyerAddress("Plot 12, MIDC Bhosari, Pune");
        invoice.setIssuedOn(LocalDate.of(2026, 10, 31));
        invoice.setDueOn(LocalDate.of(2026, 11, 7));
        Files.createDirectories(OUT);
        byte[] pdf = InvoicePdfRenderer.forSample(new HtmlPdf()).render(invoice);
        Files.write(OUT.resolve("4-invoice.pdf"), pdf);
        assertThat(pdf).isNotEmpty();
    }

    @Test
    void anIntraStateInvoicePrintsCgstAndSgstInsteadOfOneLine() throws Exception {
        SubscriptionInvoice invoice = new SubscriptionInvoice(UUID.randomUUID(), "BILL-2026-00002");
        invoice.setPlanName("Growth");
        invoice.setBillingCycle(Subscription.Cycle.MONTHLY);
        invoice.setAmount(new BigDecimal("1000.00"));
        invoice.setTaxPercent(new BigDecimal("18.00"));
        invoice.setTaxAmount(new BigDecimal("180.00"));
        invoice.setCgstAmount(new BigDecimal("90.00"));
        invoice.setSgstAmount(new BigDecimal("90.00"));
        invoice.setTotal(new BigDecimal("1180.00"));
        invoice.setPlaceOfSupply("27");
        invoice.setSacCode("998314");
        invoice.setBuyerName("Acme Forgings");
        invoice.setIssuedOn(LocalDate.of(2026, 10, 31));
        invoice.setDueOn(LocalDate.of(2026, 11, 7));

        byte[] pdf = InvoicePdfRenderer.forSample(new HtmlPdf()).render(invoice);

        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(document))
                    .contains("CGST at 9%", "SGST at 9%", "Place of supply 27", "SAC 998314")
                    .doesNotContain("GST at 18");
        }
    }
}
