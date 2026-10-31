package com.qualityos.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends account emails away from the request. A reply that waited for the mail server would reveal, by its timing,
 * whether an address has an account.
 */
@Component
class MailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MailDispatcher.class);

    private final EmailService emails;

    MailDispatcher(EmailService emails) {
        this.emails = emails;
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onMailRequested(MailRequested event) {
        try {
            emails.sendWithRetries(event.email());
        } catch (RuntimeException ex) {
            log.error("Account email could not be sent: {}", event.email(), ex);
        }
    }
}
