package com.qualityos.identity.service;

import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.tenant.TenantContext;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which facilities the current member works in. A member with no facilities assigned sees all of them, and so does a
 * background job, which has no member at all.
 */
@Service
public class FacilityScope {

    private static final String ASSIGNED = """
            SELECT f.facility_id
            FROM membership_facilities f
            JOIN tenant_memberships m ON m.tenant_id = f.tenant_id AND m.id = f.membership_id
            WHERE m.user_id = ?
            """;

    private final JdbcTemplate jdbc;

    FacilityScope(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the facilities this member is limited to, or empty when they are not limited */
    @Transactional(readOnly = true)
    public Optional<Set<UUID>> restriction() {
        UUID user = TenantContext.getCurrentUserId();
        if (user == null) {
            return Optional.empty();
        }
        Set<UUID> assigned = new HashSet<>(jdbc.queryForList(ASSIGNED, UUID.class, user));
        return assigned.isEmpty() ? Optional.empty() : Optional.of(assigned);
    }

    public boolean allows(UUID facilityId) {
        return restriction().map(allowed -> allowed.contains(facilityId)).orElse(true);
    }

    /** Refuses as if the record did not exist, so that its number cannot be confirmed by guessing. */
    public void require(Collection<UUID> facilityIds, String resource, String reference) {
        Optional<Set<UUID>> restriction = restriction();
        if (restriction.isPresent() && !restriction.get().containsAll(facilityIds)) {
            throw new ResourceNotFoundException(resource, reference);
        }
    }
}
