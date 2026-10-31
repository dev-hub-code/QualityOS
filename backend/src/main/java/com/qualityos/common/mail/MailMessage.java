package com.qualityos.common.mail;

/** Bodies can hold one-time links, so this must never be logged. */
public record MailMessage(String to, String subject, String text) {

    @Override
    public String toString() {
        return "MailMessage[" + subject + "]";
    }
}
