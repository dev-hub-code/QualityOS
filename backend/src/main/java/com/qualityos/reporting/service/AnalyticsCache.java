package com.qualityos.reporting.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.qualityos.common.audit.TenantDataChanged;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.reporting.dto.ReportingDtos.Overview;
import com.qualityos.reporting.service.ReportScope.Resolved;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Holds dashboard figures, which take seconds to work out for a large organisation.
 *
 * <p>The key carries the organisation and the facilities in scope, and the figures hold nothing else that depends
 * on who is asking. Access is checked before this is reached, so a hit never skips it. A change recorded on this
 * instance takes effect at once, and one recorded on another shows here within {@link #LIFETIME}.
 */
@Component
class AnalyticsCache {

    static final Duration LIFETIME = Duration.ofSeconds(60);

    private static final int MAX_ENTRIES = 2_000;

    private record Key(
            UUID tenantId, long generation, LocalDate from, LocalDate to, LocalDate today, ZoneId zone,
            Optional<Set<UUID>> facilities) {
    }

    private final Cache<Key, Overview> entries =
            Caffeine.newBuilder().expireAfterWrite(LIFETIME).maximumSize(MAX_ENTRIES).build();

    private final Map<UUID, AtomicLong> generations = new ConcurrentHashMap<>();

    /**
     * The generation is read first, so figures worked out while a change commits are stored where nobody will look
     * for them. Two people missing at once both work the figures out, which is cheaper than making one wait.
     */
    Overview get(Resolved scope, Supplier<Overview> compute) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        Key key = new Key(tenantId, generation(tenantId).get(), scope.from(), scope.to(), scope.today(), scope.zone(),
                scope.facilities());
        Overview found = entries.getIfPresent(key);
        if (found != null) {
            return found;
        }
        Overview computed = compute.get();
        entries.put(key, computed);
        return computed;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void changed(TenantDataChanged event) {
        generation(event.tenantId()).incrementAndGet();
    }

    private AtomicLong generation(UUID tenantId) {
        return generations.computeIfAbsent(tenantId, ignored -> new AtomicLong());
    }
}
