package com.qualityos.common.mail;

import java.util.Map;

/** What a notification's email says, kept as data so that it can be written in the recipient's language. */
public record EmailContent(EmailTemplate template, Map<String, String> variables) {
}
