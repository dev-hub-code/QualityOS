package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.common.attachment.FileStorage;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.reporting.service.ReportExports;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * What happens to a background export when things go wrong: the person loses the permission before it runs or after it
 * is ready, storing the file fails, or the job and the request reach the same export at once.
 */
@TestPropertySource(properties = {"qualityos.reports.screen-rows=2", "qualityos.reports.background-rows=6"})
class ReportExportRecoveryIT extends AbstractIntegrationTest {

    private static final String REPORTS = "/api/v1/reports";

    @Autowired
    private ReportExports exports;

    @MockitoSpyBean
    private FileStorage storage;

    private Session owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = register();
        as(owner, postJson("/api/v1/ncrs", Map.of("title", "Scratches", "description", "Seen on the line",
                "severity", "MINOR"))).andExpect(status().isCreated());
        clearInvocations(storage);
    }

    private String request(Session session) throws Exception {
        return JsonPath.read(as(session, postJson(REPORTS + "/ncr/exports", Map.of()))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(), "$.data.id");
    }

    private String column(String id, String column) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT " + column + " FROM report_exports WHERE id = ?::uuid", String.class, id);
    }

    private String awaitDone(String id) throws InterruptedException {
        for (int tries = 0; tries < 100; tries++) {
            String state = column(id, "status");
            if (!state.equals("QUEUED") && !state.equals("RUNNING")) {
                return state;
            }
            Thread.sleep(100);
        }
        return column(id, "status");
    }

    /** An export left waiting, as a restart leaves it, so the test decides when it runs. */
    private UUID queued(Session session) {
        UUID id = UUID.randomUUID();
        asDatabaseSuperuser().update("""
                INSERT INTO report_exports (id, tenant_id, requested_by, report, from_date, to_date, status)
                VALUES (?, ?, ?, 'ncr', current_date - 30, current_date, 'QUEUED')""",
                id, session.tenantId(), session.userId());
        return id;
    }

    private void run(Session session, UUID id) {
        TenantContext.runAs(session.tenantId(), session.userId(), () -> exports.run(id));
    }

    private List<String> notified(Session session) {
        return asDatabaseSuperuser().queryForList("""
                SELECT title FROM notifications WHERE tenant_id = ? AND user_id = ? AND type = 'REPORT_EXPORT_READY'
                """, String.class, session.tenantId(), session.userId());
    }

    private void changeRoles(Session member, String role) throws Exception {
        as(owner, put("/api/v1/users/" + membershipId(owner, member.userId()) + "/roles")
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("roleIds", List.of(roleId(owner, role))))))
                .andExpect(status().isOk());
    }

    @Test
    void anExportWhosePersonLostThePermissionBeforeItRanMakesNoFile() throws Exception {
        Session manager = addMember(owner, "QUALITY_MANAGER");
        UUID id = queued(manager);
        changeRoles(manager, "PRODUCTION_OPERATOR");

        run(manager, id);

        assertThat(column(id.toString(), "status")).isEqualTo("FAILED");
        assertThat(column(id.toString(), "failure")).isEqualTo("You no longer have the permission this report needs.");
        assertThat(column(id.toString(), "storage_key")).isNull();
        verify(storage, never()).store(anyString(), any());
        assertThat(notified(manager)).containsExactly("NCR report could not be exported");
    }

    @Test
    void anExportWhosePersonLeftTheOrganisationBeforeItRanMakesNoFile() throws Exception {
        Session manager = addMember(owner, "QUALITY_MANAGER");
        UUID id = queued(manager);
        as(owner, postJson("/api/v1/users/" + membershipId(owner, manager.userId()) + "/deactivate", Map.of()))
                .andExpect(status().is2xxSuccessful());

        run(manager, id);

        assertThat(column(id.toString(), "status")).isEqualTo("FAILED");
        verify(storage, never()).store(anyString(), any());
    }

    @Test
    void aReadyFileCannotBeDownloadedOnceThePermissionIsGone() throws Exception {
        Session manager = addMember(owner, "QUALITY_MANAGER");
        String id = request(manager);
        assertThat(awaitDone(id)).isEqualTo("READY");
        as(manager, get(REPORTS + "/exports/" + id + "/file")).andExpect(status().isOk());

        changeRoles(manager, "PRODUCTION_OPERATOR");

        // Refused at the door: reports themselves are no longer open to them.
        as(manager, get(REPORTS + "/exports/" + id + "/file")).andExpect(status().isForbidden());
    }

    @Test
    void aFailureStoringTheFileFailsTheExportPlainlyAndAskingAgainWorks() throws Exception {
        doThrow(new IOException("Disk full")).when(storage).store(startsWith("report-exports/"), any());

        String failed = request(owner);
        assertThat(awaitDone(failed)).isEqualTo("FAILED");
        // The person is told in plain words, never the storage error.
        assertThat(column(failed, "failure")).isEqualTo("The export could not be made. Try again, or choose fewer dates.");
        assertThat(notified(owner)).containsExactly("NCR report could not be exported");
        as(owner, get(REPORTS + "/exports/" + failed + "/file")).andExpect(status().isConflict());

        doCallRealMethod().when(storage).store(anyString(), any());
        String again = request(owner);
        assertThat(awaitDone(again)).isEqualTo("READY");
        as(owner, get(REPORTS + "/exports/" + again + "/file")).andExpect(status().isOk());
        // The failed one stays failed, and its slot is free again.
        assertThat(column(failed, "status")).isEqualTo("FAILED");
    }

    @Test
    void theJobAndTheRequestReachingTheSameExportMakeItOnce() throws Exception {
        UUID id = queued(owner);
        CyclicBarrier together = new CyclicBarrier(2);
        Runnable reach = () -> {
            try {
                together.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            run(owner, id);
        };

        CompletableFuture.allOf(CompletableFuture.runAsync(reach), CompletableFuture.runAsync(reach))
                .get(30, TimeUnit.SECONDS);

        assertThat(column(id.toString(), "status")).isEqualTo("READY");
        verify(storage, times(1)).store(startsWith("report-exports/"), any());
        assertThat(asDatabaseSuperuser().queryForObject("""
                SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND action = 'REPORT_EXPORTED'
                  AND new_state ->> 'background' = 'true'""", Integer.class, owner.tenantId())).isEqualTo(1);
        assertThat(notified(owner)).containsExactly("NCR report is ready");
    }
}
