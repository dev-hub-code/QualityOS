package com.qualityos.common.security;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Answers whether a user may act in a tenant right now, and with which permissions. */
public interface TenantAccessResolver {

    /** @return the user's permission codes, empty if the user, tenant or membership is not active */
    Optional<Set<String>> resolvePermissions(UUID tenantId, UUID userId);

    /**
     * For a support session: what the operator may do, empty once the grant has ended or been withdrawn, or the
     * operator no longer handles customers. Includes {@link #SUPPORT_CHANGES} when the grant allows changes.
     */
    Optional<Set<String>> resolveSupportPermissions(UUID tenantId, UUID operatorId, UUID grantId);

    /** Held, beside the permissions, by a support session the organisation lets make changes. */
    String SUPPORT_CHANGES = "SUPPORT_CHANGES";

    /** Adds a line to the organisation's support log. */
    void recordSupportView(UUID tenantId, UUID operatorId, UUID grantId, String detail);

    /** Adds a change support made to the organisation's support log. */
    void recordSupportChange(UUID tenantId, UUID operatorId, UUID grantId, String detail);

    /** False once an account is disabled, which ends any session it holds. */
    boolean isOpenAccount(UUID userId);
}
