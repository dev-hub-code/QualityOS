package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AccountEmailIT extends AbstractIntegrationTest {

    private static final String NEW_PASSWORD = "another-long-password";

    private String requestReset(String email) throws Exception {
        mvc.perform(postJson("/api/v1/auth/password-reset/request", Map.of("email", email)))
                .andExpect(status().isAccepted());
        return mail.awaitToken(email, "Reset");
    }

    private org.springframework.test.web.servlet.ResultActions confirmReset(String token) throws Exception {
        return mvc.perform(postJson("/api/v1/auth/password-reset/confirm",
                Map.of("token", token, "newPassword", NEW_PASSWORD)));
    }

    private org.springframework.test.web.servlet.ResultActions signIn(String email, String password) throws Exception {
        return mvc.perform(postJson("/api/v1/auth/login", Map.of("email", email, "password", password)));
    }

    @Test
    void aPasswordResetReplacesThePasswordAndEndsEverySession() throws Exception {
        Session session = register();
        String token = requestReset(session.email());

        confirmReset(token).andExpect(status().isNoContent());

        signIn(session.email(), PASSWORD).andExpect(status().isUnauthorized());
        signIn(session.email(), NEW_PASSWORD).andExpect(status().isOk());
        mvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", session.refreshToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aResetLinkWorksOnceAndOnlyUntilItExpires() throws Exception {
        Session used = register();
        String token = requestReset(used.email());
        confirmReset(token).andExpect(status().isNoContent());
        confirmReset(token).andExpect(status().isUnprocessableContent());

        Session late = register();
        String expired = requestReset(late.email());
        asAppRole(null, sql -> sql.update(
                "UPDATE account_tokens SET expires_at = now() - interval '1 minute' WHERE user_id = ?", late.userId()));
        confirmReset(expired).andExpect(status().isUnprocessableContent());

        confirmReset("not-a-real-token").andExpect(status().isUnprocessableContent());
    }

    @Test
    void anEmailVerificationLinkCannotResetAPassword() throws Exception {
        Session session = register();
        confirmReset(mail.awaitToken(session.email(), "Confirm")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void resetRequestsLookTheSameForUnknownAddressesAndAreThrottled() throws Exception {
        String stranger = uniqueEmail();
        mvc.perform(postJson("/api/v1/auth/password-reset/request", Map.of("email", stranger)))
                .andExpect(status().isAccepted());
        assertThat(mail.to(stranger)).isEmpty();

        Session session = register();
        requestReset(session.email());
        mvc.perform(postJson("/api/v1/auth/password-reset/request", Map.of("email", session.email())))
                .andExpect(status().isAccepted());
        Thread.sleep(200);
        assertThat(mail.to(session.email()).stream().filter(m -> m.subject().contains("Reset"))).hasSize(1);
    }

    @Test
    void aResetUnlocksALockedAccount() throws Exception {
        Session session = register();
        for (int attempt = 0; attempt < 10; attempt++) {
            signIn(session.email(), "wrong-password-here").andExpect(status().isUnauthorized());
        }
        signIn(session.email(), PASSWORD).andExpect(status().isUnauthorized());

        confirmReset(requestReset(session.email())).andExpect(status().isNoContent());

        signIn(session.email(), NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void registrationSendsAVerificationLink() throws Exception {
        Session session = register();
        as(session, get("/api/v1/auth/me")).andExpect(jsonPath("$.data.user.emailVerified").value(false));

        String token = mail.awaitToken(session.email(), "Confirm");
        mvc.perform(postJson("/api/v1/auth/verify-email", Map.of("token", token)))
                .andExpect(status().isNoContent());

        as(session, get("/api/v1/auth/me")).andExpect(jsonPath("$.data.user.emailVerified").value(true));
        mvc.perform(postJson("/api/v1/auth/resend-verification", Map.of("email", session.email())))
                .andExpect(status().isAccepted());
        Thread.sleep(200);
        assertThat(mail.to(session.email())).hasSize(1);
    }

    @Test
    void tokensAreStoredOnlyAsHashes() throws Exception {
        Session session = register();
        String reset = requestReset(session.email());
        String invitation = invite(session, uniqueEmail(), "VIEWER");

        asAppRole(session.tenantId(), sql -> {
            assertThat(sql.queryForObject("SELECT count(*) FROM account_tokens WHERE token_hash = ?", Long.class, reset))
                    .isZero();
            assertThat(sql.queryForObject("SELECT count(*) FROM invitations WHERE token_hash = ?", Long.class,
                    invitation.substring(invitation.indexOf('.') + 1))).isZero();
        });
    }

    @Test
    void anInvitedPersonSetsTheirOwnPasswordAndIsVerifiedByTheLink() throws Exception {
        Session owner = register();
        String email = uniqueEmail();
        String token = invite(owner, email, "VIEWER");

        mvc.perform(postJson("/api/v1/auth/invitations/preview", Map.of("token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.data.existingAccount").value(false));
        mvc.perform(postJson("/api/v1/auth/invitations/accept", Map.of(
                        "token", token, "fullName", "Nisha Rao", "password", "short")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(postJson("/api/v1/auth/invitations/accept", Map.of(
                        "token", token, "fullName", "Nisha Rao", "password", PASSWORD)))
                .andExpect(status().isOk());

        Session joined = login(email);
        assertThat(joined.tenantId()).isEqualTo(owner.tenantId());
        as(joined, get("/api/v1/auth/me")).andExpect(jsonPath("$.data.user.emailVerified").value(true));
        as(owner, get("/api/v1/invitations")).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(postJson("/api/v1/auth/invitations/accept", Map.of(
                        "token", token, "fullName", "Again", "password", PASSWORD)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void anExistingAccountMustProveItselfToJoin() throws Exception {
        Session owner = register();
        Session other = register();
        String token = invite(owner, other.email(), "VIEWER");

        mvc.perform(postJson("/api/v1/auth/invitations/accept", Map.of(
                        "token", token, "password", "wrong-password-here")))
                .andExpect(status().isUnauthorized());
        as(other, get("/api/v1/organizations")).andExpect(jsonPath("$.data.length()").value(1));

        mvc.perform(postJson("/api/v1/auth/invitations/accept", Map.of("token", token, "password", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantId").value(owner.tenantId().toString()));
        as(other, get("/api/v1/organizations")).andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void invitationsCanBeRevokedAndResent() throws Exception {
        Session owner = register();
        String email = uniqueEmail();
        String first = invite(owner, email, "VIEWER");
        as(owner, postJson("/api/v1/invitations", Map.of("email", email, "roleIds", List.of(roleId(owner, "VIEWER")))))
                .andExpect(status().isConflict());
        String id = com.jayway.jsonpath.JsonPath.read(as(owner, get("/api/v1/invitations"))
                .andReturn().getResponse().getContentAsString(), "$.data[0].id");

        as(owner, postJson("/api/v1/invitations/" + id + "/resend", Map.of())).andExpect(status().isOk());
        for (int wait = 0; wait < 100 && mail.to(email).size() < 2; wait++) {
            Thread.sleep(20);
        }
        String second = mail.awaitToken(email, "invited");
        assertThat(second).isNotEqualTo(first);
        mvc.perform(postJson("/api/v1/auth/invitations/preview", Map.of("token", first)))
                .andExpect(status().isUnprocessableContent());

        as(owner, postJson("/api/v1/invitations/" + id + "/revoke", Map.of())).andExpect(status().isNoContent());
        mvc.perform(postJson("/api/v1/auth/invitations/preview", Map.of("token", second)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void anAdministratorCannotInviteAboveTheirOwnAuthority() throws Exception {
        Session owner = register();
        Session admin = addMember(owner, "ORGANISATION_ADMIN");
        Session viewer = addMember(owner, "VIEWER");

        as(admin, postJson("/api/v1/invitations", Map.of(
                        "email", uniqueEmail(), "roleIds", List.of(roleId(owner, "ORGANISATION_OWNER")))))
                .andExpect(status().isForbidden());
        as(viewer, get("/api/v1/invitations")).andExpect(status().isForbidden());
    }

    @Test
    void aFailedMailServerDoesNotFailTheRequest() throws Exception {
        mail.setFailing(true);
        try {
            mvc.perform(postJson("/api/v1/auth/password-reset/request", Map.of("email", register().email())))
                    .andExpect(status().isAccepted());
        } finally {
            mail.setFailing(false);
        }
    }
}
