package com.qualityos.production.service;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.organization.dto.FacilityDtos.ShiftCalendar;
import com.qualityos.organization.service.FacilityService;
import com.qualityos.organization.service.OrganizationService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Periods and working time for manufacturing reports. Days are the organisation's own, in its time zone; working time
 * comes from the facilities' shifts.
 */
@Component
public class ProductionCalendar {

    private final OrganizationService organizations;
    private final FacilityService facilities;

    ProductionCalendar(OrganizationService organizations, FacilityService facilities) {
        this.organizations = organizations;
        this.facilities = facilities;
    }

    public ZoneId zone() {
        return ZoneId.of(organizations.getCurrent().timezone());
    }

    public String currency() {
        return organizations.getCurrent().currency();
    }

    /** Both days included, at most {@code maxDays}. */
    public static void requirePeriod(LocalDate from, LocalDate to, int maxDays) {
        if (to.isBefore(from)) {
            throw new BusinessRuleException("The period ends before it starts.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > maxDays) {
            throw new BusinessRuleException("Choose a period of at most %d days.".formatted(maxDays));
        }
    }

    public Instant startOf(LocalDate day, ZoneId zone) {
        return day.atStartOfDay(zone).toInstant();
    }

    /** Each facility's active shifts. */
    public Map<UUID, List<ShiftCalendar>> shifts(Collection<UUID> facilityIds) {
        return facilities.shiftsOf(facilityIds).stream().collect(Collectors.groupingBy(ShiftCalendar::facilityId));
    }

    /** Working minutes on a day from these shifts; a shift past midnight counts on the day it starts. */
    public static long workingMinutes(List<ShiftCalendar> shifts, LocalDate day) {
        return shifts.stream().filter(shift -> shift.worksOn(day.getDayOfWeek()))
                .mapToLong(ShiftCalendar::workingMinutes).sum();
    }
}
