package com.qualityos.common.mail;

import com.qualityos.common.mail.MailGateway.MailDeliveryException;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.common.util.UuidV7;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The one way email leaves the application: picks the language, renders, sends, and records what happened. */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final String DEFAULT_LANGUAGE = "en";
    private static final String DEFAULT_BRAND = "QualityOS";
    private static final int MAX_ATTEMPTS = 3;
    private static final String INSERT_LOG = """
            INSERT INTO email_delivery_logs (id, tenant_id, recipient, template, language, status, attempt, error_type,
                                             notification_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final EmailRenderer renderer;
    private final MailGateway gateway;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final List<String> languages;
    private final Duration retryDelay;

    EmailService(
            EmailRenderer renderer,
            MailGateway gateway,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${qualityos.mail.languages}") List<String> languages,
            @Value("${qualityos.mail.retry-delay}") Duration retryDelay) {
        this.renderer = renderer;
        this.gateway = gateway;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.languages = languages;
        this.retryDelay = retryDelay;
    }

    public boolean supports(String language) {
        return languages.contains(language);
    }

    /** The user's choice, then the organisation's default, then English. */
    public String language(String userLanguage, String organizationLocale) {
        if (userLanguage != null && supports(userLanguage)) {
            return userLanguage;
        }
        String organization = organizationLocale == null ? null : Locale.forLanguageTag(organizationLocale).getLanguage();
        return organization != null && supports(organization) ? organization : DEFAULT_LANGUAGE;
    }

    public RenderedEmail render(EmailRequest request) {
        Map<String, Object> variables = new HashMap<>();
        Locale locale = Locale.forLanguageTag(request.language() + "-IN");
        DateTimeFormatter dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale);
        request.variables().forEach((name, value) ->
                variables.put(name, value instanceof LocalDate date ? dates.format(date) : value));
        Object organization = variables.get("organizationName");
        variables.put("brandName", organization != null ? organization : DEFAULT_BRAND);
        return renderer.render(request.template(), request.language(), variables);
    }

    /**
     * One attempt, for callers that keep their own retry schedule.
     *
     * @throws MailDeliveryException if it was not sent
     */
    public void send(EmailRequest request) {
        attempt(request, 1);
    }

    /** Waits on the calling thread, so call this away from a request. A temporary mail outage usually passes quickly. */
    void sendWithRetries(EmailRequest request) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                attempt(request, attempt);
                return;
            } catch (MailDeliveryException ex) {
                log.warn("Email failed, attempt {} of {}: {}", attempt, MAX_ATTEMPTS, request, ex);
                if (attempt < MAX_ATTEMPTS && !pause(attempt)) {
                    return;
                }
            }
        }
    }

    private boolean pause(int attempt) {
        try {
            Thread.sleep(retryDelay.multipliedBy(attempt));
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void attempt(EmailRequest request, int attempt) {
        try {
            RenderedEmail email = render(request);
            gateway.send(new MailMessage(request.to(), email.subject(), email.text(), email.html()));
            record(request, "SENT", attempt, null);
        } catch (MailDeliveryException | EmailTemplateException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            record(request, "FAILED", attempt, cause.getClass().getSimpleName());
            throw ex instanceof MailDeliveryException delivery ? delivery : new MailDeliveryException(ex);
        }
    }

    /** A caller's open transaction is already bound to the request's tenant, and the row belongs with its outcome. */
    private void record(EmailRequest request, String status, int attempt, String errorType) {
        Runnable insert = () -> jdbc.update(INSERT_LOG, UuidV7.generate(), request.tenantId(), request.to(),
                request.template().name(), request.language(), status, attempt, errorType, request.notificationId());
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            insert.run();
        } else {
            TenantContext.runAs(request.tenantId(), null, () -> tx.executeWithoutResult(transaction -> insert.run()));
        }
    }
}
