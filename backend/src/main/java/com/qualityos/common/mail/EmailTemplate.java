package com.qualityos.common.mail;

/** Each one is a file in {@code email-templates/}, with an HTML and a plain text version. */
public enum EmailTemplate {
    VERIFICATION("auth/verification"),
    WELCOME("auth/welcome"),
    PASSWORD_RESET("auth/password-reset"),
    PASSWORD_CHANGED("auth/password-changed"),
    SECURITY_ALERT("auth/security-alert"),
    SECOND_FACTOR_ENROLLED("auth/second-factor-enrolled"),
    SECOND_FACTOR_RESET("auth/second-factor-reset"),
    INVITATION("auth/invitation"),
    INSPECTION_ASSIGNMENT("inspection/assignment"),
    INSPECTION_FAILED("inspection/failed"),
    MATERIAL_ON_HOLD("inspection/material-on-hold"),
    MATERIAL_RECEIVED("inspection/material-received"),
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
    AUDIT_DUE_SOON("audit/due-soon"),
    SUBSCRIPTION_EXPIRY("subscription/expiry"),
    PAYMENT_CONFIRMATION("subscription/payment-confirmation"),
    PRODUCTION_APPROVAL("production/approval"),
    PRODUCTION_SENT_BACK("production/sent-back"),
    PRODUCTION_STOPPED("production/stopped"),
    PRODUCTION_OVERDUE("production/overdue"),
    NCR_AWAITING_CLOSURE("ncr/awaiting-closure"),
    CAPA_ACTION_ASSIGNED("capa/action-assigned"),
    CAPA_ACTION_OVERDUE("capa/action-overdue"),
    CAPA_VERIFICATION("capa/verification"),
    INSPECTION_SENT_BACK("inspection/sent-back"),
    INSPECTION_DISPOSITION("inspection/disposition"),
    MATERIAL_CORRECTION_APPROVAL("traceability/correction-approval"),
    MATERIAL_CORRECTION_DECIDED("traceability/correction-decided"),
    SHIPMENTS_UNCONFIRMED("traceability/shipments-unconfirmed"),
    CALIBRATION_FAILED("calibration/failed"),
    AUDIT_AWAITING_CLOSURE("audit/awaiting-closure"),
    PLAN_CHANGED("subscription/plan-changed"),
    INVOICE_OVERDUE("subscription/invoice-overdue"),
    PLAN_LIMIT("subscription/plan-limit"),
    ANNOUNCEMENT("notification/announcement"),
    GENERAL("notification/general");

    private final String path;

    EmailTemplate(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
