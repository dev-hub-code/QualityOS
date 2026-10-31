package com.qualityos.common.mail;

/** Delivers one email. SMS and WhatsApp can later sit beside this, behind the notification module. */
public interface MailGateway {

    /** @throws MailDeliveryException if the message could not be handed to the mail server */
    void send(MailMessage message);

    class MailDeliveryException extends RuntimeException {

        public MailDeliveryException(Throwable cause) {
            super(cause);
        }
    }
}
