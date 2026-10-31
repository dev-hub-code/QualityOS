package com.qualityos.common.mail;

/** Publish inside a transaction: the email goes out only if that transaction commits. */
public record MailRequested(MailMessage message) {
}
