package com.qualityos.common.security;

/** Permission codes seeded by migration, as constants for {@code @PreAuthorize}. */
public final class Permissions {

    public static final String TENANT_MANAGE = "TENANT_MANAGE";
    public static final String USER_MANAGE = "USER_MANAGE";
    public static final String ROLE_MANAGE = "ROLE_MANAGE";
    public static final String AUDIT_LOG_VIEW = "AUDIT_LOG_VIEW";

    private Permissions() {
    }
}
