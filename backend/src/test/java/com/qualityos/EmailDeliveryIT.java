package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.qualityos.common.mail.EmailContent;
import com.qualityos.common.mail.EmailRequest;
import com.qualityos.common.mail.EmailService;
import com.qualityos.common.mail.EmailTemplate;
import com.qualityos.common.mail.MailMessage;
import com.qualityos.common.mail.MailRequested;
import com.qualityos.notification.service.NotificationType;
import com.qualityos.notification.service.NotificationDeliveryService;
import com.qualityos.notification.service.NotificationService;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;

class EmailDeliveryIT extends AbstractIntegrationTest {

    @Autowired
    private EmailService emails;

    @Autowired
    private NotificationService notifications;

    @Autowired
    private NotificationDeliveryService delivery;

    @Autowired
    private ApplicationEventPublisher events;

    @AfterEach
    void mailServerRecovers() {
        mail.setFailing(false);
    }

    private void chooseLanguage(Session session, String language) throws Exception {
        as(session, put("/api/v1/auth/me/email-language")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(language == null ? Map.of() : Map.of("language", language))))
                .andExpect(status().isNoContent());
    }

    private void setOrganizationLocale(Session session, String locale) {
        asAppRole(session.tenantId(), sql -> sql.update("UPDATE tenants SET locale = ? WHERE id = ?", locale, session.tenantId()));
    }

    private void capaAssigned(Session owner, Session recipient) {
        inTransactionAs(owner.tenantId(), owner.userId(), () -> {
            notifications.notify(Set.of(recipient.userId()), NotificationType.CAPA_ASSIGNED, "Title", "Body",
                    "/capas/1", new EmailContent(EmailTemplate.CAPA_ASSIGNED, Map.of(
                            "capaNumber", "CAPA-1", "capaTitle", "<b>Fix</b>", "dueDate", "2026-11-20")));
            return null;
        });
    }

    private MailMessage lastTo(Session session) {
        return mail.to(session.email()).getLast();
    }

    @Test
    void aMemberGetsTheirChosenLanguageWithALocalDate() throws Exception {
        Session owner = register();
        Session member = addMember(owner, "VIEWER");
        chooseLanguage(member, "hi");
        as(member, get("/api/v1/auth/me")).andExpect(jsonPath("$.data.user.language").value("hi"));

        capaAssigned(owner, member);
        delivery.deliverPending();

        MailMessage email = lastTo(member);
        assertThat(email.subject()).contains("CAPA-1", "सौंपा");
        assertThat(email.html()).contains("&lt;b&gt;Fix").contains("2026").contains("नवंबर");
        assertThat(email.text()).contains("<b>Fix</b>").contains("http://localhost:3000/capas/1");
    }

    @Test
    void aMemberWithoutAChoiceGetsTheOrganisationDefault() throws Exception {
        Session owner = register();
        Session member = addMember(owner, "VIEWER");
        setOrganizationLocale(owner, "mr-IN");

        capaAssigned(owner, member);
        delivery.deliverPending();
        assertThat(lastTo(member).subject()).contains("सोपवला");
    }

    @Test
    void anUnknownOrganisationLocaleFallsBackToEnglish() throws Exception {
        Session owner = register();
        Session member = addMember(owner, "VIEWER");
        setOrganizationLocale(owner, "fr-FR");

        capaAssigned(owner, member);
        delivery.deliverPending();
        assertThat(lastTo(member).subject()).isEqualTo("CAPA CAPA-1 was assigned to you");
    }

    @Test
    void anUnavailableLanguageCannotBeChosen() throws Exception {
        as(register(), put("/api/v1/auth/me/email-language")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"fr\"}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void accountEmailsFollowTheChosenLanguage() throws Exception {
        Session session = register();
        chooseLanguage(session, "mr");
        mvc.perform(postJson("/api/v1/auth/password-reset/request", Map.of("email", session.email())))
                .andExpect(status().isAccepted());
        awaitMail(session.email(), 2);
        assertThat(mail.to(session.email()).getLast().subject()).contains("पासवर्ड");
    }

    @Test
    void changingAPasswordSendsANoticeWithoutThePassword() throws Exception {
        Session session = register();
        as(session, postJson("/api/v1/auth/change-password",
                        Map.of("currentPassword", PASSWORD, "newPassword", "a-brand-new-password")))
                .andExpect(status().is2xxSuccessful());
        awaitMail(session.email(), 2);
        MailMessage notice = mail.to(session.email()).getLast();
        assertThat(notice.subject()).contains("password was changed");
        assertThat(notice.text() + notice.html()).doesNotContain("a-brand-new-password", PASSWORD);
    }

    @Test
    void lockingAnAccountSendsAnAlert() throws Exception {
        Session session = register();
        for (int attempt = 0; attempt < 10; attempt++) {
            mvc.perform(postJson("/api/v1/auth/login", Map.of("email", session.email(), "password", "wrong-password-1")));
        }
        awaitMail(session.email(), 2);
        assertThat(mail.to(session.email()).getLast().subject()).contains("Security alert");
    }

    @Test
    void aTemporaryFailureIsRetriedAndEachAttemptIsRecorded() throws Exception {
        String address = uniqueEmail().toLowerCase();
        mail.failNext(2);
        inTransactionAs(null, null, () -> {
            events.publishEvent(new MailRequested(new EmailRequest(null, address, EmailTemplate.GENERAL, "en",
                    Map.of("title", "T", "body", "B", "recipientName", "A", "actionUrl", "http://x"))));
            return null;
        });
        awaitMail(address, 1);
        awaitLogRows(address, 3);

        assertThat(logStatuses(address)).containsExactly("FAILED", "FAILED", "SENT");
    }

    @Test
    void aPermanentFailureStopsAfterThreeAttempts() throws Exception {
        String address = uniqueEmail().toLowerCase();
        mail.setFailing(true);
        inTransactionAs(null, null, () -> {
            events.publishEvent(new MailRequested(new EmailRequest(null, address, EmailTemplate.GENERAL, "en",
                    Map.of("title", "T", "body", "B", "recipientName", "A", "actionUrl", "http://x"))));
            return null;
        });
        awaitLogRows(address, 3);
        Thread.sleep(100);
        assertThat(logStatuses(address)).containsExactly("FAILED", "FAILED", "FAILED");
    }

    @Test
    void aMissingVariableIsRecordedAsAFailure() {
        String address = uniqueEmail().toLowerCase();
        assertThatThrownBy(() -> emails.send(new EmailRequest(null, address, EmailTemplate.WELCOME, "en", Map.of())))
                .isInstanceOf(com.qualityos.common.mail.MailGateway.MailDeliveryException.class);
        assertThat(logStatuses(address)).containsExactly("FAILED");
    }

    @Test
    void anOrganisationSeesOnlyItsOwnDeliveries() throws Exception {
        Session owner = register();
        Session member = addMember(owner, "VIEWER");
        Session stranger = register();
        capaAssigned(owner, member);
        delivery.deliverPending();

        String count = "SELECT count(*) FROM email_delivery_logs";
        assertThat(inTransactionAs(owner.tenantId(), null, () -> jdbc.queryForObject(count, Integer.class))).isPositive();
        assertThat(inTransactionAs(stranger.tenantId(), null, () -> jdbc.queryForObject(
                count + " WHERE tenant_id = ?", Integer.class, owner.tenantId()))).isZero();
        assertThatThrownBy(() -> asAppRole(stranger.tenantId(), sql -> sql.update("""
                INSERT INTO email_delivery_logs (id, tenant_id, recipient, template, language, status, attempt)
                VALUES (?, ?, 'a@b.co', 'GENERAL', 'en', 'SENT', 1)
                """, UUID.randomUUID(), owner.tenantId()))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void deliveryRecordsCannotBeChanged() throws Exception {
        Session owner = register();
        for (String sql : java.util.List.of(
                "UPDATE email_delivery_logs SET status = 'SENT'", "DELETE FROM email_delivery_logs")) {
            assertThatThrownBy(() -> asAppRole(owner.tenantId(), statement -> statement.update(sql)))
                    .as(sql).isInstanceOf(DataAccessException.class);
        }
    }

    private void awaitMail(String address, int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (mail.to(address).size() < count && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(mail.to(address)).hasSizeGreaterThanOrEqualTo(count);
    }

    private void awaitLogRows(String address, int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (logStatuses(address).size() < count && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private java.util.List<String> logStatuses(String address) {
        return asDatabaseSuperuser().queryForList(
                "SELECT status FROM email_delivery_logs WHERE recipient = ? ORDER BY attempt", String.class,
                address);
    }

    @Test
    void aDateIsWrittenForTheReader() {
        String address = uniqueEmail().toLowerCase();
        var email = emails.render(new EmailRequest(null, address, EmailTemplate.CAPA_DUE_REMINDER, "hi", Map.of(
                "recipientName", "A", "capaNumber", "C", "capaTitle", "T", "actionUrl", "http://x",
                "dueDate", LocalDate.of(2026, 3, 5))));
        assertThat(email.subject()).contains("मार्च");
    }
}
