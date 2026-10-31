package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReportingIT extends TraceabilityFixture {

    private static final String OVERVIEW = "/api/v1/analytics/overview";

    private String iqcTemplate;

    private String decideLot(String quantity, String value, String disposition) throws Exception {
        Lot lot = lot(material(owner, true), quantity);
        if (iqcTemplate == null) {
            iqcTemplate = template("IQC");
        }
        String inspection = idOf(body(as(owner, postJson("/api/v1/inspections",
                        Map.of("receiptItemId", lot.id(), "templateId", iqcTemplate)))
                .andExpect(status().isCreated())));
        String path = "/api/v1/inspections/" + inspection;
        as(owner, postJson(path + "/execute", Map.of("results", List.of(
                        Map.of("parameterCode", "DIA", "sampleNumber", 1, "valueNumeric", value)))))
                .andExpect(status().isOk());
        as(owner, postJson(path + "/submit", Map.of())).andExpect(status().isOk());
        as(manager, postJson(path + "/approve", Map.of())).andExpect(status().isOk());
        as(manager, postJson(path + "/disposition", Map.of("disposition", disposition))).andExpect(status().isOk());
        return inspection;
    }

    private String manualNcr() throws Exception {
        return idOf(body(as(owner, postJson("/api/v1/ncrs", Map.of("title", "Scratches", "description", "Seen on the line",
                        "severity", "MINOR")))
                .andExpect(status().isCreated())));
    }

    private String facilityOfInspection(String inspection) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT facility_id::text FROM inspections WHERE id = ?::uuid", String.class, inspection);
    }

    @Test
    void anEmptyOrganisationHasNoRatesRatherThanZeroRates() throws Exception {
        as(owner, get(OVERVIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kpis.totalInspections").value(0))
                .andExpect(jsonPath("$.data.kpis.inspectionPassRate").isEmpty())
                .andExpect(jsonPath("$.data.kpis.firstPassYield").isEmpty())
                .andExpect(jsonPath("$.data.kpis.turnaroundHours").isEmpty());
    }

    @Test
    void theFiguresAddUp() throws Exception {
        decideLot("10", "20.0", "ACCEPT");
        decideLot("30", "25.0", "REJECT");

        as(owner, get(OVERVIEW))
                .andExpect(jsonPath("$.data.kpis.totalInspections").value(2))
                .andExpect(jsonPath("$.data.kpis.passedInspections").value(1))
                .andExpect(jsonPath("$.data.kpis.failedInspections").value(1))
                .andExpect(jsonPath("$.data.kpis.inspectionPassRate").value(50.0))
                .andExpect(jsonPath("$.data.kpis.supplierRejectionRate").value(50.0))
                .andExpect(jsonPath("$.data.kpis.materialRejectionRate").value(75.0))
                .andExpect(jsonPath("$.data.kpis.defectRate").value(50.0))
                .andExpect(jsonPath("$.data.kpis.openNcrs").value(1))
                .andExpect(jsonPath("$.data.suppliers.length()").isNumber())
                .andExpect(jsonPath("$.data.ncrAgeing[0].ncrs").value(1))
                .andExpect(jsonPath("$.data.defects[0].category").value("Not categorised"))
                .andExpect(jsonPath("$.data.defects[0].cumulativeShare").value(100.0));
        as(owner, get(OVERVIEW + "?from=" + LocalDate.now().withDayOfMonth(1)))
                .andExpect(jsonPath("$.data.inspectionTrend[0].passed").value(1))
                .andExpect(jsonPath("$.data.inspectionTrend[0].failed").value(1))
                .andExpect(jsonPath("$.data.inspectionTrend[0].rejectedLots").value(1));
    }

    @Test
    void firstPassYieldCountsOnlyABatchsFirstDecision() throws Exception {
        String passed = releasedBatch(releasedLot("10").id(), "10", "10");
        String batch = startBatch(product());
        consumeLot(batch, releasedLot("10").id(), "10").andExpect(status().isOk());
        complete(batch, "10").andExpect(status().isOk());
        inspectBatch(batch, "FQC", "25.0", "REWORK");
        inspectBatch(batch, "FQC", "20.0", "ACCEPT");

        as(owner, get(OVERVIEW)).andExpect(jsonPath("$.data.kpis.firstPassYield").value(50.0));
        assertThat(passed).isNotBlank();
    }

    @Test
    void aPeriodExcludesWhatWasDecidedOutsideIt() throws Exception {
        decideLot("10", "20.0", "ACCEPT");
        String past = LocalDate.now().minusDays(20).toString();
        as(owner, get(OVERVIEW + "?from=" + past + "&to=" + LocalDate.now().minusDays(10)))
                .andExpect(jsonPath("$.data.kpis.totalInspections").value(0));
    }

    @Test
    void aPeriodMustBeSensibleAndBounded() throws Exception {
        as(owner, get(OVERVIEW + "?from=2026-02-01&to=2026-01-01")).andExpect(status().isUnprocessableEntity());
        as(owner, get(OVERVIEW + "?from=2020-01-01&to=2026-01-01")).andExpect(status().isUnprocessableEntity());
        as(owner, get("/api/v1/reports/ncr?from=2020-01-01&to=2026-01-01")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void anotherOrganisationSeesNone() throws Exception {
        String inspection = decideLot("10", "25.0", "REJECT");
        Session stranger = register();
        as(stranger, get(OVERVIEW))
                .andExpect(jsonPath("$.data.kpis.totalInspections").value(0))
                .andExpect(jsonPath("$.data.kpis.openNcrs").value(0))
                .andExpect(jsonPath("$.data.recent.length()").value(0));
        as(stranger, get("/api/v1/reports/inspection")).andExpect(jsonPath("$.data.rows.length()").value(0));
        as(stranger, get(OVERVIEW + "?facilityId=" + facilityOfInspection(inspection))).andExpect(status().isNotFound());
    }

    @Test
    void aLimitedMemberSeesOnlyTheirFacilitiesInEveryFigureAndReport() throws Exception {
        String here = decideLot("10", "25.0", "REJECT");
        String manual = manualNcr();
        String elsewhere = facility(owner);
        Session limited = addMember(owner, "QUALITY_MANAGER");
        limit(limited, elsewhere);

        as(limited, get(OVERVIEW))
                .andExpect(jsonPath("$.data.kpis.totalInspections").value(0))
                .andExpect(jsonPath("$.data.kpis.openNcrs").value(0))
                .andExpect(jsonPath("$.data.defects.length()").value(0))
                .andExpect(jsonPath("$.data.pending.inspectionsAwaitingApproval").value(0))
                .andExpect(jsonPath("$.data.recent.length()").value(0));
        for (String report : List.of("inspection", "supplier-quality", "material-rejection", "ncr", "capa",
                "batch-traceability", "audit", "calibration")) {
            as(limited, get("/api/v1/reports/" + report)).andExpect(jsonPath("$.data.rows.length()").value(0));
        }
        as(limited, get(OVERVIEW + "?facilityId=" + facilityOfInspection(here))).andExpect(status().isNotFound());
        as(limited, get("/api/v1/reports/ncr.pdf?facilityId=" + facilityOfInspection(here))).andExpect(status().isNotFound());

        as(owner, get(OVERVIEW)).andExpect(jsonPath("$.data.kpis.openNcrs").value(2));
        assertThat(manual).isNotBlank();
    }

    @Test
    void aLimitedMemberSeesTheirOwnFacilityButNotWhatCannotBePlaced() throws Exception {
        String here = decideLot("10", "25.0", "REJECT");
        manualNcr();
        Session limited = addMember(owner, "QUALITY_MANAGER");
        limit(limited, facilityOfInspection(here));

        as(limited, get(OVERVIEW))
                .andExpect(jsonPath("$.data.kpis.totalInspections").value(1))
                .andExpect(jsonPath("$.data.kpis.openNcrs").value(1));
        as(limited, get("/api/v1/reports/ncr")).andExpect(jsonPath("$.data.rows.length()").value(1));
    }

    @Test
    void aRecordRaisedByHandCountsAtTheFacilityChosenForIt() throws Exception {
        String here = facility(owner);
        Session limited = addMember(owner, "QUALITY_MANAGER");
        limit(limited, here);
        as(owner, postJson("/api/v1/ncrs", Map.of("title", "Scratches", "description", "Seen on the line",
                        "severity", "MINOR", "facilityId", here)))
                .andExpect(status().isCreated());
        as(owner, postJson("/api/v1/capas", Map.of("title", "Guard the line", "problemStatement", "Scratches recur",
                        "ownerId", owner.userId(), "facilityId", here,
                        "targetDate", LocalDate.now().plusDays(7).toString(), "effectivenessRequired", false)))
                .andExpect(status().isCreated());

        as(limited, get(OVERVIEW))
                .andExpect(jsonPath("$.data.kpis.openNcrs").value(1))
                .andExpect(jsonPath("$.data.kpis.openCapas").value(1));
        as(limited, get("/api/v1/reports/ncr")).andExpect(jsonPath("$.data.rows.length()").value(1));
        as(limited, get("/api/v1/reports/capa")).andExpect(jsonPath("$.data.rows.length()").value(1));
    }

    @Test
    void anOverdueCapaIsCountedWithItsNcrsFacility() throws Exception {
        String here = decideLot("10", "25.0", "REJECT");
        String ncr = asDatabaseSuperuser().queryForObject(
                "SELECT id::text FROM non_conformances WHERE inspection_id = ?::uuid", String.class, here);
        as(owner, postJson("/api/v1/capas", Map.of("ncrId", ncr, "title", "Fix", "problemStatement", "Oversize",
                        "ownerId", owner.userId(), "targetDate", LocalDate.now().plusDays(5).toString(),
                        "effectivenessRequired", false)))
                .andExpect(status().isCreated());
        asDatabaseSuperuser().update("UPDATE corrective_actions SET target_date = current_date - 2 WHERE ncr_id = ?::uuid",
                java.util.UUID.fromString(ncr));

        as(owner, get(OVERVIEW + "?facilityId=" + facilityOfInspection(here)))
                .andExpect(jsonPath("$.data.kpis.openCapas").value(1))
                .andExpect(jsonPath("$.data.kpis.overdueCapas").value(1));
        as(owner, get(OVERVIEW + "?facilityId=" + facility(owner)))
                .andExpect(jsonPath("$.data.kpis.openCapas").value(0));
    }

    @Test
    void aQuietMonthIsStillOnTheTrend() throws Exception {
        as(owner, get(OVERVIEW + "?from=2026-01-15&to=2026-03-15"))
                .andExpect(jsonPath("$.data.inspectionTrend.length()").value(3))
                .andExpect(jsonPath("$.data.inspectionTrend[1].month").value("2026-02"))
                .andExpect(jsonPath("$.data.capaTrend.length()").value(3));
    }

    @Test
    void aReportNamesTheChosenFacility() throws Exception {
        String inspection = decideLot("10", "20.0", "ACCEPT");
        as(owner, get("/api/v1/reports/inspection?facilityId=" + facilityOfInspection(inspection)))
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.facilityLabel").value(org.hamcrest.Matchers.not("All facilities you can see")));
    }

    @Test
    void aChosenFacilityNarrowsTheFigures() throws Exception {
        String inspection = decideLot("10", "20.0", "ACCEPT");
        as(owner, get(OVERVIEW + "?facilityId=" + facilityOfInspection(inspection)))
                .andExpect(jsonPath("$.data.kpis.totalInspections").value(1));
        as(owner, get(OVERVIEW + "?facilityId=" + facility(owner)))
                .andExpect(jsonPath("$.data.kpis.totalInspections").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"inspection", "supplier-quality", "material-rejection", "ncr", "capa", "calibration", "audit",
            "batch-traceability"})
    void everyReportExportsItsRows(String report) throws Exception {
        decideLot("10", "25.0", "REJECT");
        releasedBatch(releasedLot("10").id(), "10", "10");
        String path = "/api/v1/reports/" + report;
        String table = body(as(owner, get(path)).andExpect(status().isOk()));
        int rows = JsonPath.<List<?>>read(table, "$.data.rows").size();

        byte[] pdf = as(owner, get(path + ".pdf")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");

        byte[] excel = as(owner, get(path + ".xlsx")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(excel));
                Stream<Row> sheet = workbook.getFirstSheet().openStream()) {
            assertThat(sheet.filter(row -> row.getRowNum() > 5).count()).isEqualTo(rows);
        }
    }

    @Test
    void anUnknownReportDoesNotExist() throws Exception {
        as(owner, get("/api/v1/reports/nothing")).andExpect(status().isNotFound());
    }

    @Test
    void aReportNeedsThePermissionForWhatItLists() throws Exception {
        Session keeper = addMember(owner, "SUPPLIER_MANAGER");
        as(keeper, get("/api/v1/reports/inspection")).andExpect(status().isOk());
        as(keeper, get("/api/v1/reports/calibration")).andExpect(status().isForbidden());
        as(keeper, get("/api/v1/reports/calibration.xlsx")).andExpect(status().isForbidden());
    }

    @Test
    void analyticsNeedThePermission() throws Exception {
        Session inspector = addMember(owner, "QUALITY_INSPECTOR");
        as(inspector, get(OVERVIEW)).andExpect(status().isForbidden());
        as(inspector, get("/api/v1/reports/ncr")).andExpect(status().isForbidden());
    }

    @Test
    void anExportLeavesATraceAndAViewDoesNot() throws Exception {
        as(owner, get("/api/v1/reports/ncr")).andExpect(status().isOk());
        as(owner, get("/api/v1/reports/ncr.xlsx")).andExpect(status().isOk());
        Integer exports = asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND action = 'REPORT_EXPORTED'",
                Integer.class, owner.tenantId());
        assertThat(exports).isEqualTo(1);
    }

    @Test
    void aFormulaInARecordStaysText() throws Exception {
        as(owner, postJson("/api/v1/ncrs", Map.of("title", "=HYPERLINK(\"http://evil\")", "description", "x",
                        "severity", "MINOR")))
                .andExpect(status().isCreated());
        byte[] excel = as(owner, get("/api/v1/reports/ncr.xlsx")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(excel));
                Stream<Row> sheet = workbook.getFirstSheet().openStream()) {
            Row row = sheet.filter(candidate -> candidate.getRowNum() == 6).findFirst().orElseThrow();
            assertThat(row.getCell(1).getType()).isNotEqualTo(org.dhatim.fastexcel.reader.CellType.FORMULA);
            assertThat(row.getCell(1).getRawValue()).startsWith("=HYPERLINK");
        }
    }
}
