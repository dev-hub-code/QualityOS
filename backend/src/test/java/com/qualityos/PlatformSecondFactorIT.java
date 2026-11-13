package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.common.security.Totp;
import com.qualityos.common.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

class PlatformSecondFactorIT extends AbstractIntegrationTest {

    private static final String PLATFORM = "/api/v1/platform";
    private static final String REASON = "Lost phone";

    private Session owner;
    private Session root;
    private Session fresh;

    @BeforeEach
    void setUp() throws Exception {
        owner = register();
        root = register();
        fresh = register();
        makeOperator(root, "SUPER_ADMIN");
        asDatabaseSuperuser().update("""
                INSERT INTO platform_admins (user_id, role, reauthenticated_until)
                VALUES (?, 'SUPER_ADMIN', now() + INTERVAL '1 hour')
                """, fresh.userId());
    }

    private ResultActions login(Session session, Map<String, Object> body) throws Exception {
        return mvc.perform(postJson("/api/v1/auth/login", body));
    }

    private String challenge(Session session) throws Exception {
        return JsonPath.read(body(login(session, Map.of("email", session.email(), "password", PASSWORD))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.secondFactorRequired").value(true))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())), "$.data.challengeToken");
    }

    private ResultActions complete(String challenge, String code) throws Exception {
        return mvc.perform(postJson("/api/v1/auth/login/second-factor", Map.of("challengeToken", challenge, "code", code)));
    }

    private String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }

    private int audited(UUID user, String action) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT count(*)::int FROM audit_logs WHERE user_id = ? AND action = ?", Integer.class, user, action);
    }

    private static String wrongCode(String right) {
        return right.equals("000000") ? "000001" : "000000";
    }

    @Test
    void anOperatorWithoutAnAuthenticatorCanOnlySetOneUp() throws Exception {
        for (String path : List.of("/operators", "/plans", "/organisations?page=0&size=10")) {
            as(fresh, get(PLATFORM + path)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SECOND_FACTOR_REQUIRED"));
        }
        as(fresh, postJson(PLATFORM + "/reauthenticate", Map.of("password", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SECOND_FACTOR_REQUIRED"));
        as(fresh, get(PLATFORM + "/second-factor")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("NONE"));
        // Still a member of their own organisation, with nothing in the way there.
        as(fresh, get("/api/v1/auth/me")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions", hasItem("PLATFORM_ADMIN")));
        as(owner, get(PLATFORM + "/second-factor")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void enrolmentTakesACodeFromTheSecretAndThenOpensTheControlCenter() throws Exception {
        String enrolment = body(as(fresh, postJson(PLATFORM + "/second-factor/enrolment", Map.of()))
                .andExpect(status().isOk()));
        String secret = JsonPath.read(enrolment, "$.data.secret");
        assertThat(JsonPath.<String>read(enrolment, "$.data.otpauthUri"))
                .startsWith("otpauth://totp/ProductionOS:").contains("secret=" + secret);
        as(fresh, get(PLATFORM + "/second-factor")).andExpect(jsonPath("$.data.state").value("PENDING"));
        as(fresh, get(PLATFORM + "/operators")).andExpect(status().isForbidden());

        String code = Totp.code(Totp.fromBase32(secret), Totp.step(Instant.now()));
        as(fresh, postJson(PLATFORM + "/second-factor/enrolment/confirm", Map.of("code", wrongCode(code))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SECOND_FACTOR_FAILED"));
        as(fresh, postJson(PLATFORM + "/second-factor/enrolment/confirm", Map.of("code", "12345")))
                .andExpect(status().isBadRequest());
        as(fresh, postJson(PLATFORM + "/second-factor/enrolment/confirm", Map.of("code", code)))
                .andExpect(status().isNoContent());

        as(fresh, get(PLATFORM + "/second-factor")).andExpect(jsonPath("$.data.state").value("ENROLLED"));
        as(fresh, get(PLATFORM + "/operators")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.userId == '" + fresh.userId() + "')].secondFactor", hasItem("ENROLLED")));
        assertThat(audited(fresh.userId(), "SECOND_FACTOR_ENROLLED")).isEqualTo(1);
        assertThat(mail.to(fresh.email())).extracting(message -> message.subject())
                .contains("An authenticator app was added to your ProductionOS account");

        // Enrolled means enrolled: a second secret needs a reset first.
        as(fresh, postJson(PLATFORM + "/second-factor/enrolment", Map.of())).andExpect(status().isConflict());
    }

    @Test
    void signingInAsAnEnrolledOperatorNeedsTheCodeAndACodeWorksOnce() throws Exception {
        String challenge = challenge(root);
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + challenge))
                .andExpect(status().isUnauthorized());

        int failed = audited(root.userId(), "LOGIN_FAILED");
        String code = secondFactorCode();
        complete(challenge, wrongCode(code)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message", containsString("code is not right")));
        assertThat(audited(root.userId(), "LOGIN_FAILED")).isEqualTo(failed + 1);
        complete("not-a-token", code).andExpect(status().isUnauthorized());

        String session = body(complete(challenge, code).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString()));
        mvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + JsonPath.read(session, "$.data.accessToken")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions", hasItem("PLATFORM_ADMIN")));

        // The same code again, on a fresh challenge, is no use to anyone who saw it.
        complete(challenge(root), code).andExpect(status().isUnauthorized());
    }

    @Test
    void everyoneElseSignsInWithThePasswordAlone() throws Exception {
        login(owner, Map.of("email", owner.email(), "password", PASSWORD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString());
        // An operator who has not enrolled yet is let in, and then stopped at the Control Center's door.
        login(fresh, Map.of("email", fresh.email(), "password", PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void aSuperAdminResetsAnOperatorsAuthenticatorWhoThenEnrolsAgain() throws Exception {
        enrolSecondFactor(fresh.userId());
        as(fresh, get(PLATFORM + "/operators")).andExpect(status().isOk());

        as(root, postJson(PLATFORM + "/operators/" + fresh.userId() + "/second-factor/reset", Map.of("reason", REASON)))
                .andExpect(status().isNoContent());
        as(fresh, get(PLATFORM + "/operators")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SECOND_FACTOR_REQUIRED"));
        as(root, get(PLATFORM + "/operators"))
                .andExpect(jsonPath("$.data[?(@.userId == '" + fresh.userId() + "')].secondFactor", hasItem("NONE")));
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT new_state ->> 'reason' FROM audit_logs WHERE action = 'OPERATOR_SECOND_FACTOR_RESET' AND entity_id = ?",
                String.class, fresh.userId().toString())).isEqualTo(REASON);
        assertThat(mail.to(fresh.email())).extracting(message -> message.subject())
                .contains("Your ProductionOS authenticator was reset");
        login(fresh, Map.of("email", fresh.email(), "password", PASSWORD)).andExpect(status().isOk());

        as(root, postJson(PLATFORM + "/operators/" + UUID.randomUUID() + "/second-factor/reset", Map.of("reason", REASON)))
                .andExpect(status().isNotFound());
        asDatabaseSuperuser().update("UPDATE platform_admins SET reauthenticated_until = NULL WHERE user_id = ?", root.userId());
        as(root, postJson(PLATFORM + "/operators/" + fresh.userId() + "/second-factor/reset", Map.of("reason", REASON)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_REQUIRED"));
    }

    @Test
    void aLesserOperatorCannotResetAnyone() throws Exception {
        Session billing = register();
        makeOperator(billing, "BILLING_ADMIN");
        as(billing, postJson(PLATFORM + "/operators/" + root.userId() + "/second-factor/reset", Map.of("reason", REASON)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void theDatabaseItselfRefusesAnOrdinaryMember() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        for (String call : List.of(
                "SELECT platform_second_factor_state()",
                "SELECT platform_second_factor_begin('\\x00'::bytea)",
                "SELECT platform_second_factor_pending()",
                "SELECT platform_second_factor_confirm(1)",
                "SELECT platform_second_factor_reset(gen_random_uuid())",
                "SELECT * FROM platform_second_factors")) {
            assertThatThrownBy(() -> TenantContext.runAs(owner.tenantId(), owner.userId(),
                    () -> tx.executeWithoutResult(status -> jdbc.queryForList(call))))
                    .as(call)
                    .isInstanceOf(DataAccessException.class);
        }
    }
}
