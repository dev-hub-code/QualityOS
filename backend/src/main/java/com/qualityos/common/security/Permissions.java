package com.qualityos.common.security;

/** Permission codes seeded by migration, as constants for {@code @PreAuthorize}. */
public final class Permissions {

    public static final String TENANT_MANAGE = "TENANT_MANAGE";
    public static final String USER_MANAGE = "USER_MANAGE";
    public static final String ROLE_MANAGE = "ROLE_MANAGE";
    public static final String AUDIT_LOG_VIEW = "AUDIT_LOG_VIEW";
    public static final String SUPPLIER_VIEW = "SUPPLIER_VIEW";
    public static final String SUPPLIER_UPDATE = "SUPPLIER_UPDATE";
    public static final String PRODUCT_VIEW = "PRODUCT_VIEW";
    public static final String PRODUCT_MANAGE = "PRODUCT_MANAGE";
    public static final String PURCHASE_VIEW = "PURCHASE_VIEW";
    public static final String RECEIPT_MANAGE = "RECEIPT_MANAGE";
    public static final String INSPECTION_VIEW = "INSPECTION_VIEW";
    public static final String INSPECTION_APPROVE = "INSPECTION_APPROVE";
    public static final String DISPOSITION_RECORD = "DISPOSITION_RECORD";
    public static final String NCR_VIEW = "NCR_VIEW";
    public static final String NCR_CREATE = "NCR_CREATE";
    public static final String NCR_ASSIGN = "NCR_ASSIGN";
    public static final String NCR_CLOSE = "NCR_CLOSE";
    public static final String CAPA_VIEW = "CAPA_VIEW";
    public static final String CAPA_MANAGE = "CAPA_MANAGE";
    public static final String CAPA_APPROVE = "CAPA_APPROVE";
    public static final String TRACEABILITY_VIEW = "TRACEABILITY_VIEW";
    public static final String TRACEABILITY_MANAGE = "TRACEABILITY_MANAGE";
    public static final String RECALL_VIEW = "RECALL_VIEW";
    public static final String RECALL_MANAGE = "RECALL_MANAGE";
    public static final String RECALL_CLOSE = "RECALL_CLOSE";
    public static final String REPORT_VIEW = "REPORT_VIEW";
    public static final String SUBSCRIPTION_MANAGE = "SUBSCRIPTION_MANAGE";
    /** Not a role permission: held by those listed in platform_admins, whichever organisation they sign in to. */
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
    public static final String CALIBRATION_MANAGE = "CALIBRATION_MANAGE";
    public static final String CALIBRATION_VIEW = "CALIBRATION_VIEW";
    public static final String AUDIT_VIEW = "AUDIT_VIEW";
    public static final String AUDIT_MANAGE = "AUDIT_MANAGE";
    public static final String AUDIT_CLOSE = "AUDIT_CLOSE";
    public static final String MATERIAL_CORRECTION_REQUEST = "MATERIAL_CORRECTION_REQUEST";
    public static final String MATERIAL_CORRECTION_APPROVE = "MATERIAL_CORRECTION_APPROVE";
    public static final String INSPECTION_EXECUTE = "INSPECTION_EXECUTE";

    private Permissions() {
    }

    /** Expressions for {@code @PreAuthorize}. */
    public static final class Require {

        public static final String FACILITY_VIEW = "hasAuthority('FACILITY_VIEW')";
        public static final String FACILITY_MANAGE = "hasAuthority('FACILITY_MANAGE')";
        public static final String SUPPLIER_VIEW = "hasAuthority('SUPPLIER_VIEW')";
        public static final String SUPPLIER_CREATE = "hasAuthority('SUPPLIER_CREATE')";
        public static final String SUPPLIER_UPDATE = "hasAuthority('SUPPLIER_UPDATE')";
        public static final String SUPPLIER_APPROVE = "hasAuthority('SUPPLIER_APPROVE')";
        public static final String PRODUCT_VIEW = "hasAuthority('PRODUCT_VIEW')";
        public static final String PRODUCT_MANAGE = "hasAuthority('PRODUCT_MANAGE')";
        public static final String PURCHASE_VIEW = "hasAuthority('PURCHASE_VIEW')";
        public static final String PURCHASE_MANAGE = "hasAuthority('PURCHASE_MANAGE')";
        public static final String RECEIPT_MANAGE = "hasAuthority('RECEIPT_MANAGE')";
        public static final String TEMPLATE_VIEW = "hasAuthority('TEMPLATE_VIEW')";
        public static final String TEMPLATE_MANAGE = "hasAuthority('TEMPLATE_MANAGE')";
        public static final String TEMPLATE_PUBLISH = "hasAuthority('TEMPLATE_PUBLISH')";
        public static final String INSPECTION_VIEW = "hasAuthority('INSPECTION_VIEW')";
        public static final String INSPECTION_CREATE = "hasAuthority('INSPECTION_CREATE')";
        public static final String INSPECTION_EXECUTE = "hasAuthority('INSPECTION_EXECUTE')";
        public static final String INSPECTION_APPROVE = "hasAuthority('INSPECTION_APPROVE')";
        public static final String DISPOSITION_RECORD = "hasAuthority('DISPOSITION_RECORD')";
        public static final String NCR_VIEW = "hasAuthority('NCR_VIEW')";
        public static final String NCR_CREATE = "hasAuthority('NCR_CREATE')";
        public static final String NCR_ASSIGN = "hasAuthority('NCR_ASSIGN')";
        public static final String NCR_CLOSE = "hasAuthority('NCR_CLOSE')";
        public static final String CAPA_VIEW = "hasAuthority('CAPA_VIEW')";
        public static final String CAPA_MANAGE = "hasAuthority('CAPA_MANAGE')";
        public static final String CAPA_APPROVE = "hasAuthority('CAPA_APPROVE')";
        public static final String TRACEABILITY_VIEW = "hasAuthority('TRACEABILITY_VIEW')";
        public static final String TRACEABILITY_MANAGE = "hasAuthority('TRACEABILITY_MANAGE')";
        public static final String RECALL_VIEW = "hasAuthority('RECALL_VIEW')";
        public static final String RECALL_MANAGE = "hasAuthority('RECALL_MANAGE')";
        public static final String RECALL_CLOSE = "hasAuthority('RECALL_CLOSE')";
        public static final String REPORT_VIEW = "hasAuthority('REPORT_VIEW')";
        public static final String SUBSCRIPTION_MANAGE = "hasAuthority('SUBSCRIPTION_MANAGE')";
        public static final String PLATFORM_ADMIN = "hasAuthority('PLATFORM_ADMIN')";
        public static final String CALIBRATION_VIEW = "hasAuthority('CALIBRATION_VIEW')";
        public static final String CALIBRATION_MANAGE = "hasAuthority('CALIBRATION_MANAGE')";
        public static final String AUDIT_VIEW = "hasAuthority('AUDIT_VIEW')";
        public static final String AUDIT_MANAGE = "hasAuthority('AUDIT_MANAGE')";
        public static final String AUDIT_CLOSE = "hasAuthority('AUDIT_CLOSE')";
        public static final String MATERIAL_CORRECTION_REQUEST = "hasAuthority('MATERIAL_CORRECTION_REQUEST')";
        public static final String MATERIAL_CORRECTION_APPROVE = "hasAuthority('MATERIAL_CORRECTION_APPROVE')";

        private Require() {
        }
    }
}
