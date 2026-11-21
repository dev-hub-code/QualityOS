package com.qualityos.organization.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The days off a member's facilities have coming up, for everyone to plan around. */
public final class HolidayCalendarDtos {

    private HolidayCalendarDtos() {
    }

    public record HolidayCalendar(LocalDate from, LocalDate to, List<CalendarFacility> facilities,
            List<CalendarHoliday> holidays) {
    }

    /**
     * A facility's regular days off. {@code weeklyOffDays}: Monday 1, Tuesday 2 ... Sunday 64. {@code alternateOffDay}:
     * Monday 1 ... Sunday 7, off on the occurrences in each month that {@code alternateOffWeeks} names (1st 1, 2nd 2,
     * 3rd 4, 4th 8, 5th 16).
     */
    public record CalendarFacility(UUID id, String code, String name, int weeklyOffDays, Integer alternateOffDay,
            int alternateOffWeeks) {
    }

    /** Why a facility is closed on a day. */
    public enum HolidayKind {
        /** The facility's own. */
        HOLIDAY,
        /** Its state's, or All India's. */
        STATE_HOLIDAY,
        WEEKLY_OFF,
        ALTERNATE_OFF
    }

    /**
     * A day one facility is closed: a named holiday, or a day of its weekly or alternate days off. A named holiday on
     * a regular day off is listed once, as the holiday.
     */
    public record CalendarHoliday(LocalDate date, String name, HolidayKind kind, UUID facilityId,
            String facilityCode, String facilityName) {
    }
}
