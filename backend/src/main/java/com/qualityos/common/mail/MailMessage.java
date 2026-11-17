package com.qualityos.common.mail;

/**
 * Bodies can hold one-time links, so this must never be logged.
 *
 * @param logo the organisation's logo, which the HTML body shows as {@code cid:logo}, or null
 */
public record MailMessage(String to, String subject, String text, String html, InlineImage logo) {

    public MailMessage(String to, String subject, String text, String html) {
        this(to, subject, text, html, null);
    }

    public record InlineImage(String contentType, byte[] data) {
    }

    @Override
    public String toString() {
        return "MailMessage[" + subject + "]";
    }
}
