package com.qualityos.common.security;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Answers whether a user may act in a tenant right now, and with which permissions. */
public interface TenantAccessResolver {

    /** @return the user's permission codes, empty if the user, tenant or membership is not active */
    Optional<Set<String>> resolvePermissions(UUID tenantId, UUID userId);

    /** False once an account is disabled, which ends any session it holds. */
    boolean isOpenAccount(UUID userId);
}
