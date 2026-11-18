package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Wrong passwords lock an account per address, so that someone who knows an email address cannot keep its owner out,
 * while guessing spread over many addresses still stops everywhere the owner has never signed in from.
 */
class LoginLockoutIT extends AbstractIntegrationTest {

    private static final String OWNER_ADDRESS = "198.51.100.7";

    private ResultActions signIn(String email, String password, String address) throws Exception {
        return mvc.perform(postJson("/api/v1/auth/login", Map.of("email", email, "password", password))
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                }));
    }

    @Test
    void wrongPasswordsFromOneAddressLockOnlyThatAddress() throws Exception {
        Session session = register();
        for (int attempt = 0; attempt < 10; attempt++) {
            signIn(session.email(), "wrong-password", "203.0.113.9").andExpect(status().isUnauthorized());
        }

        signIn(session.email(), PASSWORD, "203.0.113.9").andExpect(status().isUnauthorized());
        signIn(session.email(), PASSWORD, OWNER_ADDRESS).andExpect(status().isOk());
    }

    @Test
    void guessingFromManyAddressesLocksTheAccountExceptWhereTheOwnerSignsIn() throws Exception {
        Session session = register();
        signIn(session.email(), PASSWORD, OWNER_ADDRESS).andExpect(status().isOk());

        // Five from each of ten addresses: no address reaches its own limit, the account reaches its own.
        for (int address = 1; address <= 10; address++) {
            for (int attempt = 0; attempt < 5; attempt++) {
                signIn(session.email(), "wrong-password", "192.0.2." + address).andExpect(status().isUnauthorized());
            }
        }

        signIn(session.email(), PASSWORD, "192.0.2.200").andExpect(status().isUnauthorized());
        signIn(session.email(), PASSWORD, OWNER_ADDRESS).andExpect(status().isOk());
    }

    @Test
    void lockingSeveralAddressesSendsTheOwnerOneEmail() throws Exception {
        Session session = register();
        for (int address = 1; address <= 3; address++) {
            for (int attempt = 0; attempt < 10; attempt++) {
                signIn(session.email(), "wrong-password", "203.0.113." + address).andExpect(status().isUnauthorized());
            }
        }

        assertThat(emailedSubjects(session)).filteredOn(subject -> subject.startsWith("Security alert")).hasSize(1);
    }

    @Test
    void signingInStartsThatAddressCountAgain() throws Exception {
        Session session = register();
        for (int round = 0; round < 2; round++) {
            for (int attempt = 0; attempt < 9; attempt++) {
                signIn(session.email(), "wrong-password", OWNER_ADDRESS).andExpect(status().isUnauthorized());
            }
            signIn(session.email(), PASSWORD, OWNER_ADDRESS).andExpect(status().isOk());
        }
    }
}
