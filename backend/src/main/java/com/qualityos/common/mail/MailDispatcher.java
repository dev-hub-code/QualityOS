package com.qualityos.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends account emails away from the request. A reply that waited for the mail server would reveal, by its timing,
 * whether an address has an account. These emails are not retried: their links are one-time, so a user asks again.
 */
@Component
class MailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MailDispatcher.class);

    private final MailGateway gateway;

    MailDispatcher(MailGateway gateway) {
        this.gateway = gateway;
    }

    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onMailRequested(MailRequested event) {
        try {
            gateway.send(event.message());
        } catch (RuntimeException ex) {
            log.error("Account email could not be sent: {}", event.message(), ex);
        }
    }
}
