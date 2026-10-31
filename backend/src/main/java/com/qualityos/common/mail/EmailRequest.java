package com.qualityos.common.mail;

import java.util.Map;
import java.util.UUID;

/**
 * Variables can hold one-time links, so this must never be logged.
 *
 * @param tenantId empty for account emails, which belong to no organisation
 * @param variables dates are given as {@link java.time.LocalDate} and written in the recipient's language
 */
public record EmailRequest(
        UUID tenantId, String to, EmailTemplate template, String language, Map<String, Object> variables,
        UUID notificationId) {

    public EmailRequest(UUID tenantId, String to, EmailTemplate template, String language, Map<String, Object> variables) {
        this(tenantId, to, template, language, variables, null);
    }

    @Override
    public String toString() {
        return "EmailRequest[" + template + ", " + language + "]";
    }
}
