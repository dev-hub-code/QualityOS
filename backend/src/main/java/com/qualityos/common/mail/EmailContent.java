package com.qualityos.common.mail;

import java.util.Map;

/** What a notification's email says, kept as data so that it is written when the email is sent. */
public record EmailContent(EmailTemplate template, Map<String, String> variables) {
}
