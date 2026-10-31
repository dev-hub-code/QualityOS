package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(properties = "app.registration.email-verification-required=true")
class EmailVerificationIT extends AbstractIntegrationTest {

    private ResultActions signUp(String email) throws Exception {
        return mvc.perform(postJson("/api/v1/auth/register", Map.of(
                "fullName", "Asha Rao", "email", email, "password", PASSWORD, "organizationName", "Acme")));
    }

    private ResultActions signIn(String email) throws Exception {
        return mvc.perform(postJson("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD)));
    }

    private ResultActions verify(String token) throws Exception {
        return mvc.perform(postJson("/api/v1/auth/verify-email", Map.of("token", token)));
    }

    private ResultActions resend(String email) throws Exception {
        return mvc.perform(postJson("/api/v1/auth/resend-verification", Map.of("email", email)));
    }

    private String pendingAccount() throws Exception {
        String email = uniqueEmail();
        signUp(email).andExpect(status().isAccepted());
        return email;
    }

    /** Lets another link be sent straight away, without waiting out the one minute cooldown. */
    private void ageTokens(String email, String interval) {
        asAppRole(null, sql -> sql.update("""
                UPDATE account_tokens SET created_at = created_at - CAST(? AS interval)
                WHERE user_id = (SELECT id FROM users WHERE email = ?)
                """, interval, email.toLowerCase()));
    }

    private String statusOf(String email) {
        return inTransactionAs(null, null, () -> jdbc.queryForObject(
                "SELECT status FROM users WHERE email = ?", String.class, email.toLowerCase()));
    }

    @Test
    void aNewAccountGetsNoSessionUntilItsEmailIsConfirmed() throws Exception {
        String email = uniqueEmail();
        signUp(email)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.verificationRequired").value(true))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());
        assertThat(statusOf(email)).isEqualTo("PENDING_VERIFICATION");

        signIn(email)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("EMAIL_NOT_VERIFIED"));

        verify(mail.awaitToken(email, "Confirm")).andExpect(status().isNoContent());
        assertThat(statusOf(email)).isEqualTo("ACTIVE");
        signIn(email)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());
    }

    @Test
    void confirmingSendsAWelcomeOnce() throws Exception {
        String email = pendingAccount();
        verify(mail.awaitToken(email, "Confirm")).andExpect(status().isNoContent());
        Thread.sleep(200);
        assertThat(mail.to(email)).extracting(message -> message.subject())
                .containsExactly("Confirm your QualityOS email address", "Welcome to QualityOS");
    }

    @Test
    void aWrongPasswordLearnsNothingAboutAPendingAccount() throws Exception {
        String email = pendingAccount();
        mvc.perform(postJson("/api/v1/auth/login", Map.of("email", email, "password", "wrong-password-here")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void aReusedLinkIsRefused() throws Exception {
        String token = mail.awaitToken(pendingAccount(), "Confirm");
        verify(token).andExpect(status().isNoContent());
        verify(token).andExpect(status().isUnprocessableContent());
    }

    @Test
    void anExpiredLinkIsRefusedAndAFreshOneWorks() throws Exception {
        String email = pendingAccount();
        String token = mail.awaitToken(email, "Confirm");
        asAppRole(null, sql -> sql.update("UPDATE account_tokens SET expires_at = now() - interval '1 minute' "
                + "WHERE user_id = (SELECT id FROM users WHERE email = ?)", email.toLowerCase()));

        verify(token)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(
                        "This link is not valid or has expired. Please ask for a new one."));
        assertThat(statusOf(email)).isEqualTo("PENDING_VERIFICATION");

        ageTokens(email, "2 minutes");
        resend(email).andExpect(status().isAccepted());
        verify(mail.awaitNewToken(email, "Confirm", token)).andExpect(status().isNoContent());
    }

    @Test
    void anInvalidLinkGetsTheSameAnswerAsAnExpiredOne() throws Exception {
        verify("not-a-real-token")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(
                        "This link is not valid or has expired. Please ask for a new one."));
    }

    @Test
    void aNewLinkReplacesTheOldOne() throws Exception {
        String email = pendingAccount();
        String first = mail.awaitToken(email, "Confirm");
        ageTokens(email, "2 minutes");
        resend(email).andExpect(status().isAccepted());
        String second = mail.awaitNewToken(email, "Confirm", first);

        verify(first).andExpect(status().isUnprocessableContent());
        verify(second).andExpect(status().isNoContent());
    }

    @Test
    void resendingIsThrottledAndCapped() throws Exception {
        String email = pendingAccount();
        mail.awaitToken(email, "Confirm");
        resend(email).andExpect(status().isAccepted());
        Thread.sleep(200);
        assertThat(mail.to(email)).hasSize(1);

        for (int sent = 2; sent <= 7; sent++) {
            ageTokens(email, "2 minutes");
            resend(email).andExpect(status().isAccepted());
        }
        Thread.sleep(300);
        assertThat(mail.to(email)).hasSize(5);
    }

    @Test
    void resendLooksTheSameForEveryAddress() throws Exception {
        String stranger = uniqueEmail();
        resend(stranger).andExpect(status().isAccepted());
        Thread.sleep(200);
        assertThat(mail.to(stranger)).isEmpty();

        String verified = pendingAccount();
        verify(mail.awaitToken(verified, "Confirm")).andExpect(status().isNoContent());
        ageTokens(verified, "2 minutes");
        Thread.sleep(200);
        int before = mail.to(verified).size();
        resend(verified).andExpect(status().isAccepted());
        Thread.sleep(200);
        assertThat(mail.to(verified)).hasSize(before);
    }

    @Test
    void registeringATakenAddressLooksLikeSuccessAndChangesNothing() throws Exception {
        String email = pendingAccount();
        String token = mail.awaitToken(email, "Confirm");
        verify(token).andExpect(status().isNoContent());

        mvc.perform(postJson("/api/v1/auth/register", Map.of(
                        "fullName", "Intruder", "email", email.toUpperCase(),
                        "password", "a-different-password", "organizationName", "Other")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.verificationRequired").value(true));

        signIn(email).andExpect(status().isOk());
        mvc.perform(postJson("/api/v1/auth/login", Map.of("email", email, "password", "a-different-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registeringAgainWhilePendingSendsNoExtraAccount() throws Exception {
        String email = pendingAccount();
        mail.awaitToken(email, "Confirm");
        signUp(email).andExpect(status().isAccepted());
        assertThat(inTransactionAs(null, null, () -> jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?", Integer.class, email.toLowerCase()))).isEqualTo(1);
    }

    @Test
    void aVerifiedAccountReportsItsStatus() throws Exception {
        String email = pendingAccount();
        verify(mail.awaitToken(email, "Confirm")).andExpect(status().isNoContent());
        String token = JsonPath.read(
                signIn(email).andReturn().getResponse().getContentAsString(), "$.data.accessToken");

        mvc.perform(get("/api/v1/auth/verification-status").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.emailVerified").value(true))
                .andExpect(jsonPath("$.data.verificationRequired").value(true));
        mvc.perform(get("/api/v1/auth/verification-status")).andExpect(status().isUnauthorized());
    }

    @Test
    void confirmingWritesToTheAuditHistory() throws Exception {
        String email = pendingAccount();
        verify(mail.awaitToken(email, "Confirm")).andExpect(status().isNoContent());
        assertThat(asDatabaseSuperuser().queryForObject("""
                SELECT count(*) FROM audit_logs
                WHERE action = 'EMAIL_VERIFIED' AND entity_id = (SELECT id::text FROM users WHERE email = ?)
                """, Integer.class, email.toLowerCase())).isEqualTo(1);
    }
}
