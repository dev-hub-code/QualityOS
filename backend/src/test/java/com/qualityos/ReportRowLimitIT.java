package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The screen and the PDF stop early, while an Excel download holds a longer period in one file. */
@TestPropertySource(properties = {"qualityos.reports.screen-rows=2", "qualityos.reports.excel-rows=4"})
class ReportRowLimitIT extends AbstractIntegrationTest {

    private static final String NCRS = "/api/v1/reports/ncr";

    private void raiseNcrs(Session owner, int count) throws Exception {
        for (int n = 0; n < count; n++) {
            as(owner, postJson("/api/v1/ncrs", Map.of("title", "Scratches " + n, "description", "Seen on the line",
                    "severity", "MINOR"))).andExpect(status().isCreated());
        }
    }

    private List<String> excelLines(Session owner) throws Exception {
        byte[] excel = as(owner, get(NCRS + ".xlsx")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(excel));
                Stream<Row> sheet = workbook.getFirstSheet().openStream()) {
            return sheet.filter(row -> row.getRowNum() > 5)
                    .map(row -> row.getCellText(0))
                    .toList();
        }
    }

    @Test
    void excelHoldsMoreRowsThanTheScreenAndSaysWhereItStops() throws Exception {
        Session owner = register();
        raiseNcrs(owner, 5);

        as(owner, get(NCRS))
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.truncated").value(true));

        List<String> lines = excelLines(owner);
        assertThat(lines.stream().filter(line -> line.startsWith("NCR-"))).hasSize(4);
        assertThat(lines).contains("This report stops at 4 rows. Choose fewer dates to see the rest.");
    }

    @Test
    void aReportWithinTheExcelLimitIsWholeThere() throws Exception {
        Session owner = register();
        raiseNcrs(owner, 3);

        as(owner, get(NCRS)).andExpect(jsonPath("$.data.truncated").value(true));
        List<String> lines = excelLines(owner);
        assertThat(lines.stream().filter(line -> line.startsWith("NCR-"))).hasSize(3);
        assertThat(lines).noneMatch(line -> line.startsWith("This report stops"));
    }
}
