package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

class AuthFlowIT extends AbstractIntegrationTest {

    @Autowired
    private JwtEncoder realEncoder;

    @Test
    void registrationCreatesOwnerOfANewOrganization() throws Exception {
        Session session = register();

        as(session, get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.email").value(session.email().toLowerCase(Locale.ROOT)))
                .andExpect(jsonPath("$.data.tenantId").value(session.tenantId().toString()))
                .andExpect(jsonPath("$.data.onboarded").value(false))
                .andExpect(jsonPath("$.data.permissions", hasItem("TENANT_MANAGE")))
                .andExpect(content().string(not(containsString("passwordHash"))));
    }

    @Test
    void passwordIsStoredAsArgon2() throws Exception {
        Session session = register();

        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, session.userId());

        assertThat(hash).startsWith("$argon2id$");
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        String email = uniqueEmail();
        register(email);

        mvc.perform(postJson("/api/v1/auth/register", Map.of(
                        "fullName", "Other", "email", email.toUpperCase(Locale.ROOT),
                        "password", PASSWORD, "organizationName", "Other")))
                .andExpect(status().isConflict());
    }

    @Test
    void weakPasswordIsRejected() throws Exception {
        mvc.perform(postJson("/api/v1/auth/register", Map.of(
                        "fullName", "Other", "email", uniqueEmail(), "password", "short", "organizationName", "Other")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.violations[0].field").value("password"));
    }

    @Test
    void loginFailsIdenticallyForWrongPasswordAndUnknownEmail() throws Exception {
        Session session = register();

        String wrongPassword = mvc.perform(postJson("/api/v1/auth/login",
                        Map.of("email", session.email(), "password", "not-the-password")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = mvc.perform(postJson("/api/v1/auth/login",
                        Map.of("email", uniqueEmail(), "password", "not-the-password")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(wrongPassword, "$.error.message"))
                .isEqualTo(JsonPath.<String>read(unknownEmail, "$.error.message"));
    }

    @Test
    void repeatedFailuresLockTheAccountEvenForTheRightPassword() throws Exception {
        Session session = register();
        for (int i = 0; i < 10; i++) {
            mvc.perform(postJson("/api/v1/auth/login", Map.of("email", session.email(), "password", "wrong-password")))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(postJson("/api/v1/auth/login", Map.of("email", session.email(), "password", PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requestsWithoutAToken_areRejectedInTheStandardEnvelope() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void garbageTokenIsRejected() throws Exception {
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedByAnotherKeyIsRejected() throws Exception {
        Session session = register();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair attacker = generator.generateKeyPair();
        JwtEncoder forger = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(
                new RSAKey.Builder((RSAPublicKey) attacker.getPublic()).privateKey(attacker.getPrivate()).build())));

        String forged = encode(forger, session, "qualityos", "qualityos-api", Instant.now().plusSeconds(60));

        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenAndWrongAudienceAndWrongIssuerAreRejected() throws Exception {
        Session session = register();
        Instant future = Instant.now().plusSeconds(60);

        for (String token : List.of(
                encode(realEncoder, session, "qualityos", "qualityos-api", Instant.now().minusSeconds(3600)),
                encode(realEncoder, session, "qualityos", "another-api", future),
                encode(realEncoder, session, "someone-else", "qualityos-api", future))) {
            mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void refreshRotatesTheToken() throws Exception {
        Session session = register();

        String body = refresh(session.refreshToken()).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String rotated = JsonPath.read(body, "$.data.refreshToken");
        assertThat(rotated).isNotEqualTo(session.refreshToken());
        refresh(rotated).andExpect(status().isOk());
    }

    @Test
    void reusingARotatedTokenAfterTheLeewayRevokesTheWholeFamily() throws Exception {
        Session session = register();
        String successor = JsonPath.read(refresh(session.refreshToken())
                .andReturn().getResponse().getContentAsString(), "$.data.refreshToken");
        ageConsumedTokens(session.userId());

        refresh(session.refreshToken()).andExpect(status().isUnauthorized());

        refresh(successor).andExpect(status().isUnauthorized());
    }

    @Test
    void repeatingATokenWithinTheLeewayIsAToleratedRace() throws Exception {
        Session session = register();

        refresh(session.refreshToken()).andExpect(status().isOk());

        refresh(session.refreshToken()).andExpect(status().isOk());
    }

    @Test
    void refreshTokenIsStoredOnlyAsAHash() throws Exception {
        Session session = register();

        Integer plain = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE token_hash = ?", Integer.class, session.refreshToken());

        assertThat(plain).isZero();
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        Session session = register();

        mvc.perform(postJson("/api/v1/auth/logout", Map.of("refreshToken", session.refreshToken())))
                .andExpect(status().isNoContent());

        refresh(session.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void changingPasswordRevokesSessionsAndRequiresTheCurrentPassword() throws Exception {
        Session session = register();

        as(session, postJson("/api/v1/auth/change-password",
                        Map.of("currentPassword", "wrong-current-password", "newPassword", "a-brand-new-password")))
                .andExpect(status().isUnauthorized());
        as(session, postJson("/api/v1/auth/change-password",
                        Map.of("currentPassword", PASSWORD, "newPassword", "a-brand-new-password")))
                .andExpect(status().isNoContent());

        refresh(session.refreshToken()).andExpect(status().isUnauthorized());
        mvc.perform(postJson("/api/v1/auth/login", Map.of("email", session.email(), "password", "a-brand-new-password")))
                .andExpect(status().isOk());
    }

    @Test
    void deactivationTakesEffectOnTheNextRequestDespiteAValidToken() throws Exception {
        Session owner = register();
        Session member = addMember(owner, "VIEWER");
        as(member, get("/api/v1/auth/me")).andExpect(status().isOk());

        as(owner, postJson("/api/v1/users/" + membershipId(owner, member.userId()) + "/deactivate", Map.of()))
                .andExpect(status().isNoContent());

        as(member, get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        refresh(member.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void criticalActionsAreAudited() throws Exception {
        Session session = register();

        as(session, get("/api/v1/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].action", hasItem("USER_REGISTERED")))
                .andExpect(jsonPath("$.data.items[*].action", hasItem("ORGANIZATION_CREATED")));
    }

    @Test
    void onboardingCompletesOnFirstProfileUpdateAndStaleVersionsConflict() throws Exception {
        Session session = register();
        String path = "/api/v1/organizations/" + session.tenantId();
        Map<String, Object> update = new java.util.HashMap<>(Map.of(
                "name", "Acme Forgings", "gstin", "27ABCDE1234F1Z5", "countryCode", "IN",
                "timezone", "Asia/Kolkata", "currency", "INR", "locale", "en-IN", "version", 0));

        as(session, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                        .contentType("application/json").content(json.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.onboarded").value(true));

        as(session, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                        .contentType("application/json").content(json.writeValueAsString(update)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String token) throws Exception {
        return mvc.perform(postJson("/api/v1/auth/refresh", Map.of("refreshToken", token)));
    }

    private void ageConsumedTokens(UUID userId) {
        jdbc.update("UPDATE refresh_tokens SET consumed_at = now() - interval '1 hour' "
                + "WHERE user_id = ? AND consumed_at IS NOT NULL", userId);
    }

    private static String encode(JwtEncoder encoder, Session session, String issuer, String audience, Instant expires) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(session.userId().toString())
                .claim("tid", session.tenantId().toString())
                .issuedAt(expires.minusSeconds(30))
                .expiresAt(expires)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }
}
