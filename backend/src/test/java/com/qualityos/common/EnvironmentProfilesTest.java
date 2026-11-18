package com.qualityos.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Each environment's profile, as the real configuration files resolve it, with nothing else started. */
class EnvironmentProfilesTest {

    @Configuration
    static class Nothing {
    }

    private record Resolved(List<String> profiles, String mail, String environment, String smtpHost,
            String allowEphemeralKeys) {
    }

    private static Resolved resolve(String profile) {
        SpringApplication application = new SpringApplication(Nothing.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles(profile);
        try (ConfigurableApplicationContext context = application.run()) {
            Environment env = context.getEnvironment();
            return new Resolved(List.of(env.getActiveProfiles()), env.getProperty("qualityos.mail.provider"),
                    env.getProperty("qualityos.environment"), env.getProperty("spring.mail.host"),
                    env.getProperty("qualityos.security.jwt.allow-ephemeral-keys", "false"));
        }
    }

    @Test
    void devAndSitSendEveryEmailToMailpit() {
        for (String profile : List.of("dev", "sit")) {
            Resolved resolved = resolve(profile);
            assertThat(resolved.profiles()).contains(profile, "local").doesNotContain("prod");
            assertThat(resolved.mail()).isEqualTo("smtp");
            assertThat(resolved.smtpHost()).isEqualTo("mailpit");
            assertThat(resolved.environment()).isEqualTo(profile.toUpperCase());
        }
    }

    @Test
    void uatRunsAsProductionDoesAndSendsRealEmailThroughSendGrid() {
        Resolved resolved = resolve("uat");

        assertThat(resolved.profiles()).contains("uat", "prod").doesNotContain("local");
        assertThat(resolved.mail()).isEqualTo("sendgrid");
        assertThat(resolved.environment()).isEqualTo("UAT");
        // As in production, it needs real signing keys rather than throwaway ones.
        assertThat(resolved.allowEphemeralKeys()).isEqualTo("false");
    }

    @Test
    void localAndProductionAddNothingToSubjects() {
        assertThat(resolve("local").environment()).isEmpty();
        assertThat(resolve("prod").environment()).isEmpty();
        assertThat(resolve("local").mail()).isEqualTo("smtp");
    }
}
