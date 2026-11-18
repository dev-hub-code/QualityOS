package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.reporting.service.ReportExports;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Reports longer than the screen go to Excel in the background, for the person who asked and only for them. */
@TestPropertySource(properties = {"qualityos.reports.screen-rows=2", "qualityos.reports.background-rows=6"})
class ReportExportIT extends AbstractIntegrationTest {

    private static final String REPORTS = "/api/v1/reports";

    @Autowired
    private ReportExports exports;

    private Session owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = register();
    }

    private void raiseNcrs(int count) throws Exception {
        for (int n = 0; n < count; n++) {
            as(owner, postJson("/api/v1/ncrs", Map.of("title", "Scratches " + n, "description", "Seen on the line",
                    "severity", "MINOR"))).andExpect(status().isCreated());
        }
    }

    private String request(Session session, Map<String, Object> body) throws Exception {
        return JsonPath.read(as(session, postJson(REPORTS + "/ncr/exports", body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.title").value("NCR report"))
                .andReturn().getResponse().getContentAsString(), "$.data.id");
    }

    private String statusOf(String id) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT status FROM report_exports WHERE id = ?::uuid", String.class, id);
    }

    /** The export starts away from the request, so wait for it. */
    private String awaitDone(String id) throws InterruptedException {
        for (int tries = 0; tries < 100; tries++) {
            String state = statusOf(id);
            if (!state.equals("QUEUED") && !state.equals("RUNNING")) {
                return state;
            }
            Thread.sleep(100);
        }
        return statusOf(id);
    }

    private List<String> lines(byte[] excel) throws Exception {
        try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(excel));
                Stream<Row> sheet = workbook.getFirstSheet().openStream()) {
            return sheet.filter(row -> row.getRowNum() > 5).map(row -> row.getCellText(0)).toList();
        }
    }

    private void insert(UUID id, Session session, String state, String extra) {
        asDatabaseSuperuser().update("""
                INSERT INTO report_exports (id, tenant_id, requested_by, report, from_date, to_date, status, created_at,
                    started_at, row_count, storage_key, expires_at)
                VALUES (?, ?, ?, 'ncr', current_date - 30, current_date, ?, now() - INTERVAL '2 minutes', """ + extra + ")",
                id, session.tenantId(), session.userId(), state);
    }

    private int tidy() {
        return TenantContext.callAs(owner.tenantId(), null, () -> exports.tidy());
    }

    @Test
    void aLongReportIsExportedInTheBackgroundAndOnlyItsRequesterDownloadsIt() throws Exception {
        raiseNcrs(8);
        as(owner, get(REPORTS + "/ncr")).andExpect(jsonPath("$.data.truncated").value(true));

        String id = request(owner, Map.of());
        assertThat(awaitDone(id)).as(() -> asDatabaseSuperuser().queryForObject(
                "SELECT failure FROM report_exports WHERE id = ?::uuid", String.class, id)).isEqualTo("READY");
        as(owner, get(REPORTS + "/exports"))
                .andExpect(jsonPath("$.data[0].id").value(id))
                .andExpect(jsonPath("$.data[0].status").value("READY"))
                .andExpect(jsonPath("$.data[0].rowCount").value(6))
                .andExpect(jsonPath("$.data[0].truncated").value(true))
                .andExpect(jsonPath("$.data[0].expiresAt").isNotEmpty());

        byte[] file = as(owner, get(REPORTS + "/exports/" + id + "/file")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        List<String> lines = lines(file);
        assertThat(lines.stream().filter(line -> line.startsWith("NCR-"))).hasSize(6);
        assertThat(lines).contains("This report stops at 6 rows. Choose fewer dates to see the rest.");

        assertThat(asDatabaseSuperuser().queryForObject("""
                SELECT count(*) FROM notifications WHERE tenant_id = ? AND user_id = ? AND type = 'REPORT_EXPORT_READY'
                """, Integer.class, owner.tenantId(), owner.userId())).isEqualTo(1);
        assertThat(asDatabaseSuperuser().queryForObject("""
                SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND action = 'REPORT_EXPORTED'
                  AND new_state ->> 'background' = 'true' AND new_state ->> 'rows' = '6'
                """, Integer.class, owner.tenantId())).isEqualTo(1);

        // A colleague who may see the same report sees neither the export nor its file.
        Session colleague = addMember(owner, "QUALITY_MANAGER");
        as(colleague, get(REPORTS + "/exports")).andExpect(jsonPath("$.data.length()").value(0));
        as(colleague, get(REPORTS + "/exports/" + id + "/file")).andExpect(status().isNotFound());
        // Nor does another organisation.
        as(register(), get(REPORTS + "/exports/" + id + "/file")).andExpect(status().isNotFound());
    }

    @Test
    void aPeriodOrPermissionThatWillNotDoIsRefusedAtOnce() throws Exception {
        Map<String, Object> backwards = new HashMap<>();
        backwards.put("from", LocalDate.now().toString());
        backwards.put("to", LocalDate.now().minusDays(3).toString());
        as(owner, postJson(REPORTS + "/ncr/exports", backwards)).andExpect(status().isUnprocessableContent());
        as(owner, postJson(REPORTS + "/no-such/exports", Map.of())).andExpect(status().isNotFound());
        Session viewer = addMember(owner, "PRODUCTION_OPERATOR");
        as(viewer, postJson(REPORTS + "/ncr/exports", Map.of())).andExpect(status().isForbidden());
    }

    @Test
    void aPersonHasAFewExportsBeingMadeAtOnceAtMost() throws Exception {
        for (int n = 0; n < 3; n++) {
            asDatabaseSuperuser().update("""
                    INSERT INTO report_exports (id, tenant_id, requested_by, report, from_date, to_date, status)
                    VALUES (?, ?, ?, 'ncr', current_date - 30, current_date, 'RUNNING')""",
                    UUID.randomUUID(), owner.tenantId(), owner.userId());
        }
        as(owner, postJson(REPORTS + "/ncr/exports", Map.of())).andExpect(status().isConflict());
    }

    @Test
    void theJobMakesWhatARestartLeftAndClearsWhatIsCutOffOrExpired() throws Exception {
        raiseNcrs(1);
        UUID waiting = UUID.randomUUID();
        insert(waiting, owner, "QUEUED", "NULL, NULL, NULL, NULL");
        UUID cut = UUID.randomUUID();
        insert(cut, owner, "RUNNING", "now() - INTERVAL '2 hours', NULL, NULL, NULL");
        UUID old = UUID.randomUUID();
        insert(old, owner, "READY", "now() - INTERVAL '8 days', 1, 'report-exports/gone.xlsx', now() - INTERVAL '1 day'");
        asAppRole(null, sql -> assertThat(sql.queryForList("SELECT tenants_with_report_exports_to_tidy()", UUID.class))
                .contains(owner.tenantId()));

        assertThat(tidy()).isEqualTo(3);
        assertThat(statusOf(waiting.toString())).isEqualTo("READY");
        assertThat(statusOf(cut.toString())).isEqualTo("FAILED");
        assertThat(statusOf(old.toString())).isEqualTo("EXPIRED");
        as(owner, get(REPORTS + "/exports/" + old + "/file")).andExpect(status().isConflict());
        as(owner, get(REPORTS + "/exports"))
                .andExpect(jsonPath("$.data[?(@.id == '" + cut + "')].failure").value(
                        org.hamcrest.Matchers.hasItem("The export was interrupted. Export it again.")));
        assertThat(tidy()).isZero();
    }
}
