package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;

/** The settings a real server such as Gmail needs reach the mail sender, and TLS cannot be skipped. */
@TestPropertySource(properties = {
    "SMTP_HOST=smtp.gmail.com", "SMTP_PORT=587", "SMTP_USERNAME=sender@example.com", "SMTP_PASSWORD=app-password",
    "SMTP_AUTH=true", "SMTP_STARTTLS=true"
})
class SmtpSettingsIT extends AbstractIntegrationTest {

    @Autowired
    private JavaMailSenderImpl sender;

    @Test
    void aRealServerGetsCredentialsAndRequiredTls() {
        assertThat(sender.getHost()).isEqualTo("smtp.gmail.com");
        assertThat(sender.getPort()).isEqualTo(587);
        assertThat(sender.getUsername()).isEqualTo("sender@example.com");
        assertThat(sender.getPassword()).isEqualTo("app-password");
        assertThat(sender.getJavaMailProperties())
                .containsEntry("mail.smtp.auth", "true")
                .containsEntry("mail.smtp.starttls.enable", "true")
                .containsEntry("mail.smtp.starttls.required", "true");
    }
}
