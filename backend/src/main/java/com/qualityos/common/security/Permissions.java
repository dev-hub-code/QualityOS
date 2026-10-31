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

        private Require() {
        }
    }
}
