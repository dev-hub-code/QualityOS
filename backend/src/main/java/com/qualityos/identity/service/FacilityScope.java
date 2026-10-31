package com.qualityos.identity.service;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.tenant.TenantContext;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
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

    private static final String LIMITED_ELSEWHERE = """
            SELECT m.user_id
            FROM membership_facilities f
            JOIN tenant_memberships m ON m.tenant_id = f.tenant_id AND m.id = f.membership_id
            GROUP BY m.user_id
            HAVING NOT bool_or(f.facility_id = ?)
            """;

    private static final String LIMITED_ANYWHERE = """
            SELECT DISTINCT m.user_id
            FROM membership_facilities f
            JOIN tenant_memberships m ON m.tenant_id = f.tenant_id AND m.id = f.membership_id
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

    /** Narrows a list of people to those who can open this facility's records, before anyone is told about one. */
    @Transactional(readOnly = true)
    public Set<UUID> allowedIn(UUID facilityId, Set<UUID> users) {
        Set<UUID> limited = new HashSet<>(facilityId == null
                ? jdbc.queryForList(LIMITED_ANYWHERE, UUID.class)
                : jdbc.queryForList(LIMITED_ELSEWHERE, UUID.class, facilityId));
        Set<UUID> allowed = new HashSet<>(users);
        allowed.removeAll(limited);
        return allowed;
    }

    public boolean allows(UUID facilityId) {
        return restriction().map(allowed -> allowed.contains(facilityId)).orElse(true);
    }

    /** Keeps a list to this member's facilities. A record with no facility is left out for a limited member. */
    public <T> Specification<T> limit(Specification<T> filter, String attribute) {
        Optional<Set<UUID>> restriction = restriction();
        return restriction.isEmpty()
                ? filter
                : filter.and((root, query, builder) -> root.get(attribute).in(restriction.get()));
    }

    /** As {@link #require}, for a single facility that may be missing. */
    public void requireAllowed(UUID facilityId, String resource, String reference) {
        if (!allows(facilityId)) {
            throw new ResourceNotFoundException(resource, reference);
        }
    }

    /** A new record must be placed in one of this member's facilities, or they could not open it afterwards. */
    public void requirePlaced(UUID facilityId) {
        if (facilityId == null && restriction().isPresent()) {
            throw new BusinessRuleException("Choose a facility.");
        }
        if (facilityId != null) {
            requireAllowed(facilityId, "Facility", facilityId.toString());
        }
    }

    /** Someone made responsible for a record must be able to open it. */
    public void requireAllowedFor(UUID userId, UUID facilityId) {
        if (allowedIn(facilityId, Set.of(userId)).isEmpty()) {
            throw new BusinessRuleException("This person does not work at this record's facility.");
        }
    }

    /** Refuses as if the record did not exist, so that its number cannot be confirmed by guessing. */
    public void require(Collection<UUID> facilityIds, String resource, String reference) {
        Optional<Set<UUID>> restriction = restriction();
        if (restriction.isPresent() && !restriction.get().containsAll(facilityIds)) {
            throw new ResourceNotFoundException(resource, reference);
        }
    }
}
