package com.qualityos.common.mail;

/** Each one is a file in {@code email-templates/<language>/}, with an HTML and a plain text version. */
public enum EmailTemplate {
    VERIFICATION("auth/verification"),
    WELCOME("auth/welcome"),
    PASSWORD_RESET("auth/password-reset"),
    PASSWORD_CHANGED("auth/password-changed"),
    SECURITY_ALERT("auth/security-alert"),
    INVITATION("auth/invitation"),
    INSPECTION_ASSIGNMENT("inspection/assignment"),
    INSPECTION_FAILED("inspection/failed"),
    MATERIAL_ON_HOLD("inspection/material-on-hold"),
    NCR_CREATED("ncr/created"),
    NCR_ASSIGNED("ncr/assigned"),
    CAPA_ASSIGNED("capa/assigned"),
    CAPA_DUE_REMINDER("capa/due-reminder"),
    CAPA_OVERDUE("capa/overdue"),
    CAPA_CLOSURE("capa/closure"),
    CALIBRATION_DUE("calibration/due"),
    CALIBRATION_OVERDUE("calibration/overdue"),
    AUDIT_SCHEDULED("audit/scheduled"),
    AUDIT_FINDINGS("audit/findings"),
    SUBSCRIPTION_EXPIRY("subscription/expiry"),
    PAYMENT_CONFIRMATION("subscription/payment-confirmation"),
    GENERAL("notification/general");

    private final String path;

    EmailTemplate(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
