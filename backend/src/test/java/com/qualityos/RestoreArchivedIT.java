package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An archived record can be listed on request and brought back, by those who may archive it. */
class RestoreArchivedIT extends TraceabilityFixture {

    private List<String> ids(String path, boolean archived) throws Exception {
        return JsonPath.read(body(as(owner, get("/api/v1" + path + "?size=100&archived=" + archived))
                .andExpect(status().isOk())), "$.data.items[*].id");
    }

    private void archiveAndRestore(String path, String id) throws Exception {
        as(owner, delete("/api/v1" + path + "/" + id)).andExpect(status().isNoContent());
        assertThat(ids(path, false)).doesNotContain(id);
        assertThat(ids(path, true)).contains(id);

        as(owner, postJson("/api/v1" + path + "/" + id + "/restore", Map.of())).andExpect(status().isNoContent());
        assertThat(ids(path, false)).contains(id);
        assertThat(ids(path, true)).doesNotContain(id);
        as(owner, postJson("/api/v1" + path + "/" + id + "/restore", Map.of()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.endsWith("is not archived.")));
    }

    @Test
    void masterDataComesBackFromTheArchive() throws Exception {
        archiveAndRestore("/products", product());
        archiveAndRestore("/materials", material(owner, true));
        archiveAndRestore("/suppliers", draftSupplier(owner));
        archiveAndRestore("/facilities", facility(owner));
        archiveAndRestore("/inspection-templates", template("IQC"));
        String checklist = idOf(body(as(owner, postJson("/api/v1/audit-checklists", Map.of("code", code(),
                "name", "Process audit", "items", List.of(Map.of("question", "Is the control plan current?")))))
                .andExpect(status().isCreated())));
        archiveAndRestore("/audit-checklists", checklist);
        String centre = idOf(body(as(owner, postJson("/api/v1/manufacturing/work-centers", Map.of(
                "code", "WC-" + code(), "name", "Turning", "facilityId", facility(owner))))
                .andExpect(status().isCreated())));
        archiveAndRestore("/manufacturing/work-centers", centre);

        assertThat(asDatabaseSuperuser().queryForList(
                "SELECT action FROM audit_logs WHERE tenant_id = ?::uuid AND action LIKE '%_RESTORED' ORDER BY 1",
                String.class, owner.tenantId().toString())).containsExactly("AUDIT_CHECKLIST_RESTORED",
                "FACILITY_RESTORED", "MATERIAL_RESTORED", "PRODUCT_RESTORED", "SUPPLIER_RESTORED",
                "TEMPLATE_RESTORED", "WORK_CENTER_RESTORED");
    }

    @Test
    void onlyThoseWhoMayArchiveMayRestore() throws Exception {
        String product = product();
        as(owner, delete("/api/v1/products/" + product)).andExpect(status().isNoContent());
        Session viewer = addMember(owner, "VIEWER");
        as(viewer, postJson("/api/v1/products/" + product + "/restore", Map.of())).andExpect(status().isForbidden());
    }
}
