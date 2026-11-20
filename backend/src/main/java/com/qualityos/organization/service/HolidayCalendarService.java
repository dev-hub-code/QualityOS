package com.qualityos.organization.service;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.identity.service.FacilityScope;
import com.qualityos.organization.dto.HolidayCalendarDtos.CalendarFacility;
import com.qualityos.organization.dto.HolidayCalendarDtos.CalendarHoliday;
import com.qualityos.organization.dto.HolidayCalendarDtos.HolidayCalendar;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Upcoming days off at the facilities a member can open; anyone in the organisation can read it. */
@Service
public class HolidayCalendarService {

    static final int DEFAULT_DAYS = 90;
    static final int MAX_DAYS = 366;

    private final JdbcTemplate jdbc;
    private final FacilityScope scope;
    private final OrganizationService organizations;

    HolidayCalendarService(JdbcTemplate jdbc, FacilityScope scope, OrganizationService organizations) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.organizations = organizations;
    }

    /** From today in the organisation's time zone for 90 days, when no period is given. Both days included. */
    @Transactional(readOnly = true)
    public HolidayCalendar calendar(LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now(ZoneId.of(organizations.getCurrent().timezone()));
        LocalDate end = to != null ? to : start.plusDays(DEFAULT_DAYS - 1);
        if (end.isBefore(start)) {
            throw new BusinessRuleException("The period ends before it starts.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            throw new BusinessRuleException("Choose a period of at most %d days.".formatted(MAX_DAYS));
        }
        // An empty array matches nothing, so an unlimited member is passed as null.
        UUID[] allowed = scope.restriction().map((Set<UUID> ids) -> ids.toArray(UUID[]::new)).orElse(null);
        List<CalendarFacility> facilities = jdbc.query("""
                SELECT id, code, name, weekly_off_days, alternate_off_day, alternate_off_weeks FROM facilities
                WHERE archived_at IS NULL AND (?::uuid[] IS NULL OR id = ANY (?::uuid[]))
                ORDER BY name
                """, (rows, row) -> new CalendarFacility(rows.getObject("id", UUID.class), rows.getString("code"),
                rows.getString("name"), rows.getInt("weekly_off_days"),
                rows.getObject("alternate_off_day", Integer.class), rows.getInt("alternate_off_weeks")),
                allowed, allowed);
        List<CalendarHoliday> holidays = jdbc.query("""
                SELECT h.holiday_on, h.name, f.id, f.code, f.name AS facility_name
                FROM facility_holidays h JOIN facilities f ON f.id = h.facility_id
                WHERE f.archived_at IS NULL AND h.holiday_on BETWEEN ? AND ?
                  AND (?::uuid[] IS NULL OR f.id = ANY (?::uuid[]))
                ORDER BY h.holiday_on, f.name
                """, (rows, row) -> new CalendarHoliday(rows.getObject("holiday_on", LocalDate.class),
                rows.getString("name"), rows.getObject("id", UUID.class), rows.getString("code"),
                rows.getString("facility_name")), start, end, allowed, allowed);
        return new HolidayCalendar(start, end, facilities, holidays);
    }
}
