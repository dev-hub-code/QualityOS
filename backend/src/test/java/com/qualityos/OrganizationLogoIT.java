package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qualityos.common.mail.MailMessage;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

class OrganizationLogoIT extends AbstractIntegrationTest {

    /** The PNG signature followed by a few bytes: enough to be recognised, which is all the server checks. */
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    private ResultActions upload(Session session, String name, String claimedType, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/v1/organizations/current/logo")
                .file(new MockMultipartFile("file", name, claimedType, content))
                .header(HttpHeaders.AUTHORIZATION, session.bearer()));
    }

    @Test
    void anOwnerSetsALogoThatEveryMemberSeesAndEveryEmailCarries() throws Exception {
        Session owner = register();
        Session member = addMember(owner, "VIEWER");
        as(member, get("/api/v1/organizations/current/logo")).andExpect(status().isNotFound());

        upload(owner, "logo.png", "image/png", PNG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentType").value("image/png"))
                .andExpect(jsonPath("$.data.sizeBytes").value(PNG.length));

        String etag = as(member, get("/api/v1/organizations/current/logo"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        as(member, get("/api/v1/organizations/current/logo").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());

        // An email for the organisation shows it, attached inline.
        String invited = uniqueEmail();
        invite(owner, invited, "VIEWER");
        MailMessage email = mail.to(invited).getLast();
        assertThat(email.html()).contains("src=\"cid:logo\"");
        assertThat(email.logo()).isNotNull();
        assertThat(Arrays.equals(email.logo().data(), PNG)).isTrue();
        assertThat(email.logo().contentType()).isEqualTo("image/png");

        // Another organisation has none, and its emails carry none.
        Session other = register();
        as(other, get("/api/v1/organizations/current/logo")).andExpect(status().isNotFound());
        String elsewhere = uniqueEmail();
        invite(other, elsewhere, "VIEWER");
        assertThat(mail.to(elsewhere).getLast().logo()).isNull();
        assertThat(mail.to(elsewhere).getLast().html()).doesNotContain("cid:logo");

        mvc.perform(delete("/api/v1/organizations/current/logo").header(HttpHeaders.AUTHORIZATION, member.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/organizations/current/logo").header(HttpHeaders.AUTHORIZATION, owner.bearer()))
                .andExpect(status().isNoContent());
        as(member, get("/api/v1/organizations/current/logo")).andExpect(status().isNotFound());
    }

    @Test
    void onlyASmallPngOrJpegIsTakenWhateverItClaimsToBe() throws Exception {
        Session owner = register();
        Session member = addMember(owner, "VIEWER");
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);

        upload(owner, "logo.png", "image/png", svg)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value("The logo must be a PNG or JPEG image."));
        byte[] large = Arrays.copyOf(PNG, 256 * 1024 + 1);
        upload(owner, "big.png", "image/png", large)
                .andExpect(jsonPath("$.error.message").value("The logo must be 256 KB or smaller."));
        upload(member, "logo.png", "image/png", PNG).andExpect(status().isForbidden());

        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16};
        upload(owner, "photo.bin", "application/octet-stream", jpeg)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentType").value("image/jpeg"));
    }
}
