package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;

import com.qualityos.inspection.dto.InspectionDtos.InspectionHistory;
import com.qualityos.quality.dto.QualityDtos.NcrHistory;
import com.qualityos.recall.dto.RecallDtos.ClosureReview;
import com.qualityos.recall.dto.RecallDtos.HistoryEntry;
import com.qualityos.recall.dto.RecallDtos.RecallDetail;
import com.qualityos.recall.dto.RecallDtos.RecallReport;
import com.qualityos.recall.dto.RecallDtos.ReportCustomer;
import com.qualityos.recall.dto.RecallDtos.ReportProduct;
import com.qualityos.recall.service.RecallPdfRenderer;
import com.qualityos.traceability.dto.TraceabilityDtos.RecallLine;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** Writes each sample to {@code target/recall-samples}, so that a person can look at them as well. */
class RecallPdfSamplesTest {

    private static final Path OUTPUT = Path.of("target/recall-samples");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 28);
    private static final String LONG_NAME = "Maharashtra Precision Automotive Components and Allied Engineering "
            + "Industries Private Limited (Chakan Plant Unit No. 2, Stores Receiving)";

    private final RecallPdfRenderer renderer = new RecallPdfRenderer();

    private record Delivered(String name, int dispatched, int returned, String status, String exception, long attempts) {
    }

    private static BigDecimal number(int value) {
        return BigDecimal.valueOf(value).setScale(6);
    }

    private static RecallReport report(
            List<Delivered> delivered, List<String> batches, int serialCount, List<HistoryEntry> history) {
        List<ReportCustomer> customers = new ArrayList<>();
        List<RecallLine> lines = new ArrayList<>();
        for (int i = 0; i < delivered.size(); i++) {
            Delivered one = delivered.get(i);
            int left = one.dispatched() - one.returned();
            customers.add(new ReportCustomer("CUST-" + (1000 + i), one.name(), "Asha Rao",
                    "quality.manager@customer" + i + ".example.com", "+91 98200 1" + (1000 + i),
                    "Plot " + (i + 1) + ", MIDC Bhosari, Pune 411026", one.status(), "Meera Iyer", one.attempts(),
                    one.status().equals("RESOLVED") ? DATE : null, one.exception(),
                    number(one.dispatched()), number(one.returned()), number(left)));
            lines.add(new RecallLine(UUID.randomUUID(), "DSP-2026-" + (1000 + i), UUID.randomUUID(), one.name(),
                    DATE.minusDays(9), DATE.minusDays(8), number(one.dispatched()), number(one.returned()), number(left)));
        }
        int dispatched = delivered.stream().mapToInt(Delivered::dispatched).sum();
        int returned = delivered.stream().mapToInt(Delivered::returned).sum();
        ClosureReview review = new ClosureReview(delivered.size(), 1, 1, 0, List.of(), number(dispatched),
                number(returned), number(dispatched - returned), null, List.of());
        RecallDetail detail = new RecallDetail(UUID.randomUUID(), "RCL-2026-00007", DATE, "Hairline cracks were found in "
                + "drive shafts from these batches during a customer's incoming inspection. Metallurgy points to "
                + "incorrect heat treatment on furnace 3.", "Stop using the affected units at once. Quarantine "
                + "remaining stock, and tell us how many units you hold.\nDo not scrap anything: we will arrange "
                + "collection within five working days.", null, "OPEN", batches, null, null, null, null, null, review);
        List<ReportProduct> products = batches.stream()
                .map(batch -> new ReportProduct("DS-40", "Drive Shaft 40mm", batch, "RELEASED")).toList();
        List<String> serials = IntStream.range(0, serialCount).mapToObj(i -> "DS40-2026-%06d".formatted(i)).toList();
        List<InspectionHistory> inspections = batches.stream().map(batch -> new InspectionHistory(UUID.randomUUID(),
                "INS-2026-00412", "FQC", batch, "COMPLETED", "PASS", "ACCEPT", Instant.parse("2026-10-10T06:30:00Z"))).toList();
        List<NcrHistory> ncrs = List.of();
        return new RecallReport("Acme Forgings", "Acme Forgings Private Limited",
                "Gat 121, Chakan Industrial Area, Pune 410501", "27ABCDE1234F1Z5", Instant.parse("2026-10-31T06:30:00Z"),
                "31 Oct 2026, 12:00 IST", detail, products, customers, lines, serials, inspections, ncrs, history);
    }

    private static HistoryEntry entry(String customer, String method, String outcome, String response, int day) {
        return new HistoryEntry(UUID.randomUUID(), "ATTEMPT", UUID.randomUUID(), customer, method, "Asha Rao",
                "+91 98200 11000", Instant.parse("2026-10-2%dT05:00:00Z".formatted(day)), LocalDate.of(2026, 10, 20 + day), outcome,
                response, null,
                "Meera Iyer");
    }

    private int write(String name, RecallReport report, String... expected) throws IOException {
        Files.createDirectories(OUTPUT);
        byte[] pdf = renderer.render(report);
        Files.write(OUTPUT.resolve(name + ".pdf"), pdf);
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            int pages = document.getNumberOfPages();
            assertThat(text).contains(expected).contains("Page " + pages + " of " + pages);
            PDFTextStripper perPage = new PDFTextStripper();
            for (int page = 1; page <= pages; page++) {
                perPage.setStartPage(page);
                perPage.setEndPage(page);
                // Headers and footers alone are three or four short lines. A page should have content too.
                assertThat(perPage.getText(document).lines().count()).as("page %d of %s", page, name).isGreaterThan(4);
            }
            return pages;
        }
    }

    @Test
    void oneCustomer() throws IOException {
        write("1-one-customer", report(List.of(new Delivered("Sahyadri Motors Limited", 100, 0, "PENDING", null, 0)),
                List.of("PB-2026-00031"), 12, List.of()), "Sahyadri Motors Limited", "Page 1 of 1".substring(0, 6));
    }

    @Test
    void manyCustomersWithLongNames() throws IOException {
        List<Delivered> many = new ArrayList<>();
        IntStream.range(0, 60).forEach(i -> many.add(new Delivered(
                i % 4 == 0 ? LONG_NAME : "Customer Number " + i + " Limited", 100 + i, 0, "CONTACT_ATTEMPTED", null, 1)));
        assertThat(write("2-many-customers", report(many, List.of("PB-2026-00031"), 40, List.of()),
                "Customer Number 59 Limited")).isGreaterThan(2);
    }

    @Test
    void aLargeSerialList() throws IOException {
        assertThat(write("3-large-serial-list",
                report(List.of(new Delivered("Sahyadri Motors Limited", 2500, 0, "PENDING", null, 0)),
                        List.of("PB-2026-00031"), 2500, List.of()),
                "DS40-2026-000000", "DS40-2026-002499", "(2500)")).isGreaterThan(1);
    }

    @Test
    void severalBatches() throws IOException {
        write("4-multiple-batches", report(List.of(new Delivered("Sahyadri Motors Limited", 500, 0, "PENDING", null, 0)),
                IntStream.range(0, 12).mapToObj(i -> "PB-2026-000" + (10 + i)).toList(), 30, List.of()),
                "PB-2026-00010", "PB-2026-00021");
    }

    @Test
    void returnsContactHistoryAndAnUnreachableCustomer() throws IOException {
        List<Delivered> mixed = List.of(
                new Delivered("Partial Returns Limited", 100, 30, "RETURN_IN_PROGRESS", null, 2),
                new Delivered("Fully Returned Limited", 80, 80, "RESOLVED", null, 3),
                new Delivered("Vanished Traders", 20, 0, "UNREACHABLE", "Company has ceased trading, confirmed by the "
                        + "Registrar of Companies. Agreed with the Plant Manager.", 4));
        List<HistoryEntry> history = List.of(
                entry("Fully Returned Limited", "EMAIL", "NO_REPLY", null, 1),
                entry("Fully Returned Limited", "PHONE", "FAILED", null, 2),
                entry("Fully Returned Limited", "WHATSAPP", "REACHED", "Will return all 80 units", 3),
                entry("Vanished Traders", "PHONE", "FAILED", "Number disconnected", 4));
        write("5-returns-and-contact-history", report(mixed, List.of("PB-2026-00031"), 200, history),
                "Vanished Traders", "ceased", "Number disconnected", "WhatsApp", "70", "200");
    }

    @Test
    void aCustomerNamedInHindi() throws IOException {
        write("6-devanagari-name", report(List.of(new Delivered("महाराष्ट्र ऑटो कंपोनेंट्स लिमिटेड", 10, 0, "PENDING", null, 0)),
                List.of("PB-2026-00031"), 3, List.of()), "PB-2026-00031");
    }
}
