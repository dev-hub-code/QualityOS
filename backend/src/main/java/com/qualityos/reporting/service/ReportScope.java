package com.qualityos.reporting.service;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.organization.service.FacilityService;
import com.qualityos.organization.service.OrganizationService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

/**
 * What a report may read: a bounded period by the organisation's calendar, and the facilities this member may see.
 * Anything with no facility fails the clause, so a limited member never sees what cannot be placed.
 */
@Component
class ReportScope {

    private static final int DEFAULT_DAYS = 90;
    private static final int MAX_DAYS = 732;

    private final FacilityScope scope;
    private final OrganizationService organizations;
    private final FacilityService facilities;

    ReportScope(FacilityScope scope, OrganizationService organizations, FacilityService facilities) {
        this.scope = scope;
        this.facilities = facilities;
        this.organizations = organizations;
    }

    record Resolved(
            LocalDate from, LocalDate to, ZoneId zone, LocalDate today, Optional<Set<UUID>> facilities, UUID facilityId) {

        MapSqlParameterSource params() {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("start", OffsetDateTime.from(from.atStartOfDay(zone)))
                    .addValue("end", OffsetDateTime.from(to.plusDays(1).atStartOfDay(zone)))
                    .addValue("from", from)
                    .addValue("to", to)
                    .addValue("zone", zone.getId())
                    .addValue("today", today);
            facilities.ifPresent(allowed -> params.addValue("facilities", allowed));
            return params;
        }

        /** @param column the SQL expression that holds a record's facility */
        String facility(String column) {
            return facilities.isPresent() ? " AND " + column + " IN (:facilities)" : "";
        }
    }

    Resolved resolve(LocalDate from, LocalDate to, UUID facilityId) {
        ZoneId zone = ZoneId.of(organizations.getCurrent().timezone());
        LocalDate today = LocalDate.now(zone);
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_DAYS - 1) : from;
        if (start.isAfter(end)) {
            throw new BusinessRuleException("The start date is after the end date.");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_DAYS) {
            throw new BusinessRuleException("Choose a period of two years or less.");
        }
        Optional<Set<UUID>> limit = scope.restriction();
        if (facilityId != null) {
            facilities.get(facilityId);
            scope.require(Set.of(facilityId), "Facility", facilityId.toString());
            limit = Optional.of(Set.of(facilityId));
        }
        return new Resolved(start, end, zone, today, limit, facilityId);
    }
}
