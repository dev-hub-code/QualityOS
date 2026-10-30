package com.qualityos.common.tenant;

import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.exception.QualityOsException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tenant and user the current thread acts for. Set only from verified identity: by the security filter for
 * requests, and through {@link #callAs} by authentication and background jobs.
 *
 * <p>Transactions bind this context to the database when they begin, so it cannot change inside one.
 */
public final class TenantContext {

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** @throws QualityOsException if there is no tenant, so that a missing context can never mean "all tenants" */
    public static UUID getCurrentTenantId() {
        return findCurrentTenantId().orElseThrow(() ->
                new QualityOsException(ErrorCode.INTERNAL_ERROR, "No tenant context"));
    }

    public static Optional<UUID> findCurrentTenantId() {
        Scope scope = CURRENT.get();
        return scope == null ? Optional.empty() : Optional.ofNullable(scope.tenantId());
    }

    public static UUID getCurrentUserId() {
        return findCurrentUserId().orElseThrow(() ->
                new QualityOsException(ErrorCode.INTERNAL_ERROR, "No user context"));
    }

    public static Optional<UUID> findCurrentUserId() {
        Scope scope = CURRENT.get();
        return scope == null ? Optional.empty() : Optional.ofNullable(scope.userId());
    }

    /** @param tenantId null while a user has not chosen a tenant, such as during login */
    public static <T> T callAs(UUID tenantId, UUID userId, Supplier<T> work) {
        try (Scope ignored = open(tenantId, userId)) {
            return work.get();
        }
    }

    public static void runAs(UUID tenantId, UUID userId, Runnable work) {
        callAs(tenantId, userId, () -> {
            work.run();
            return null;
        });
    }

    /** Opens a scope that restores the previous one when closed. */
    public static Scope open(UUID tenantId, UUID userId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Tenant context cannot change inside a transaction");
        }
        Scope scope = new Scope(tenantId, userId, CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    public record Scope(UUID tenantId, UUID userId, Scope previous) implements AutoCloseable {

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
