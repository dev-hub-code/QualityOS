package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;

import com.qualityos.auditmanagement.dto.AuditDtos.AuditDetail;
import com.qualityos.auditmanagement.dto.AuditDtos.AuditReport;
import com.qualityos.auditmanagement.dto.AuditDtos.AuditReview;
import com.qualityos.auditmanagement.dto.AuditDtos.FindingView;
import com.qualityos.auditmanagement.dto.AuditDtos.ItemView;
import com.qualityos.auditmanagement.service.AuditPdfRenderer;
import com.qualityos.common.report.HtmlPdf;
import com.qualityos.quality.dto.QualityDtos.LinkedCapa;
import com.qualityos.quality.dto.QualityDtos.NcrOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Writes sample reports to target/audit-samples, to be looked at. A test can only prove that they render. */
class AuditPdfSamplesTest {

    private static final Path OUT = Path.of("target/audit-samples");

    private final AuditPdfRenderer renderer = new AuditPdfRenderer(new HtmlPdf());

    private static AuditReport report(int questions, List<FindingView> findings) {
        List<ItemView> items = IntStream.rangeClosed(1, questions)
                .mapToObj(n -> new ItemView(UUID.randomUUID(), n,
                        "Are measuring instruments labelled with their calibration status and stored safely? (" + n + ")",
                        "7.1." + n, n % 5 == 0 ? "NONCONFORMING" : "CONFORMING",
                        n % 5 == 0 ? "See the findings." : null))
                .toList();
        AuditDetail audit = new AuditDetail(UUID.randomUUID(), "AUD-2026-00004", "SUPPLIER", "Annual supplier audit",
                "Heat treatment and final inspection at the Chakan plant.", UUID.randomUUID(), "Pune Plant",
                UUID.randomUUID(), "Bharat Steels", null, null, "Supplier process audit", LocalDate.of(2026, 10, 20),
                UUID.randomUUID(), "Meera Iyer", "COMPLETED", "The plant follows its procedures, with the exceptions below.",
                Instant.now(), Instant.now(), null, null, null, null, null, null, items, findings,
                new AuditReview(questions, questions, questions - questions / 5, questions / 5, 0, List.of()));
        return new AuditReport("Acme Forgings", "Acme Forgings Private Limited", "Gat 121, Chakan, Pune 410501",
                "31 Oct 2026, 12:00 IST", audit);
    }

    private static FindingView finding(int number, String grade, boolean ncr) {
        return new FindingView(UUID.randomUUID(), number, grade,
                "Caliper VC-9 was in use without a calibration label, and nobody could say when it was last checked.",
                "Seen at station 4 on 20 October. Register page 12 shows no entry for this instrument since March.",
                "7.1.5", 5, ncr ? new NcrOutcome(UUID.randomUUID(), "NCR-2026-0001" + number, "ACTION_REQUIRED", false,
                        List.of(new LinkedCapa(UUID.randomUUID(), "CAPA-2026-00003", "Label all gauges", "IMPLEMENTATION")))
                        : null);
    }

    @Test
    void writesSamples() throws Exception {
        Files.createDirectories(OUT);
        byte[] clean = renderer.render(report(5, List.of()));
        byte[] full = renderer.render(report(70, IntStream.rangeClosed(1, 14)
                .mapToObj(n -> finding(n, n % 3 == 0 ? "OBSERVATION" : "MAJOR_NONCONFORMITY", n % 3 != 0))
                .toList()));
        Files.write(OUT.resolve("1-no-findings.pdf"), clean);
        Files.write(OUT.resolve("2-many-findings.pdf"), full);
        assertThat(new String(full, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }
}
