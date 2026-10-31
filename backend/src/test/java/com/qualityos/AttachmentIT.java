package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

class AttachmentIT extends MasterDataFixture {

    private static final byte[] PDF = "%PDF-1.7 certificate".getBytes(StandardCharsets.UTF_8);

    private ResultActions upload(Session session, String entityType, String entityId, String name, byte[] content)
            throws Exception {
        return mvc.perform(multipart("/api/v1/attachments")
                .file(new MockMultipartFile("file", name, "application/pdf", content))
                .param("entityType", entityType)
                .param("entityId", entityId)
                .header(HttpHeaders.AUTHORIZATION, session.bearer()));
    }

    @Test
    void fileRoundTripsWithSafeDownloadHeaders() throws Exception {
        Session owner = register();
        String supplier = draftSupplier(owner);

        String id = idOf(body(upload(owner, "SUPPLIER", supplier, "iso-cert.pdf", PDF)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.contentType").value("application/pdf"))));

        byte[] downloaded = as(owner, get("/api/v1/attachments/" + id + "/content"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(PDF);
    }

    @Test
    void contentIsCheckedRatherThanTheDeclaredType() throws Exception {
        Session owner = register();
        byte[] html = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);

        upload(owner, "SUPPLIER", draftSupplier(owner), "certificate.pdf", html)
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void clientPathsAreStrippedFromFileNames() throws Exception {
        Session owner = register();

        upload(owner, "SUPPLIER", draftSupplier(owner), "../../etc/passwd.pdf", PDF)
                .andExpect(jsonPath("$.data.fileName").value("passwd.pdf"));
    }

    @Test
    void anotherTenantCannotListDownloadOrDelete() throws Exception {
        Session owner = register();
        Session other = register();
        String supplier = draftSupplier(owner);
        String id = idOf(body(upload(owner, "SUPPLIER", supplier, "cert.pdf", PDF)));

        as(other, get("/api/v1/attachments").param("entityType", "SUPPLIER").param("entityId", supplier))
                .andExpect(status().isNotFound());
        as(other, get("/api/v1/attachments/" + id + "/content")).andExpect(status().isNotFound());
        as(other, delete("/api/v1/attachments/" + id)).andExpect(status().isNotFound());
        upload(other, "SUPPLIER", supplier, "planted.pdf", PDF).andExpect(status().isNotFound());
    }

    @Test
    void accessFollowsThePermissionsOfTheParentRecord() throws Exception {
        Session owner = register();
        String supplier = draftSupplier(owner);
        String id = idOf(body(upload(owner, "SUPPLIER", supplier, "cert.pdf", PDF)));
        Session viewer = addMember(owner, "VIEWER");

        as(viewer, get("/api/v1/attachments/" + id + "/content")).andExpect(status().isOk());
        upload(viewer, "SUPPLIER", supplier, "x.pdf", PDF).andExpect(status().isForbidden());
        as(viewer, delete("/api/v1/attachments/" + id)).andExpect(status().isForbidden());
    }

    @Test
    void unknownEntityTypeIsRefused() throws Exception {
        Session owner = register();

        upload(owner, "NOT_A_TYPE", draftSupplier(owner), "x.pdf", PDF).andExpect(status().isNotFound());
    }

    @Test
    void removedFilesDisappearButAreRetained() throws Exception {
        Session owner = register();
        String supplier = draftSupplier(owner);
        String id = idOf(body(upload(owner, "SUPPLIER", supplier, "cert.pdf", PDF)));

        as(owner, delete("/api/v1/attachments/" + id)).andExpect(status().isNoContent());

        as(owner, get("/api/v1/attachments/" + id + "/content")).andExpect(status().isNotFound());
        as(owner, get("/api/v1/attachments").param("entityType", "SUPPLIER").param("entityId", supplier))
                .andExpect(jsonPath("$.data.length()").value(0));
        assertThat(inTransactionAs(owner.tenantId(), owner.userId(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM attachments WHERE deleted_at IS NOT NULL", Long.class))).isEqualTo(1);
    }
}
