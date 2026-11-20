package com.qualityos.organization.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class FacilityDtos {

    private FacilityDtos() {
    }

    public record SaveFacilityRequest(
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 500) String address,
            @Size(max = 100) String city,
            @Size(max = 100) String state,
            @Size(max = 20) String postalCode,
            @Pattern(regexp = "^$|^[0-9]{2}[A-Z0-9]{13}$", message = "must be a 15 character GSTIN") String gstin,
            Long version) {
    }

    public record FacilityResponse(
            UUID id,
            String code,
            String name,
            String address,
            String city,
            String state,
            String postalCode,
            String gstin,
            long version) {
    }

    public record FacilityDetail(
            FacilityResponse facility,
            List<UnitResponse> departments,
            List<UnitResponse> productionLines,
            List<ShiftResponse> shifts,
            /* From the start of this year on. */
            List<HolidayResponse> holidays,
            /* The days of the week it never works, as for a shift's days of the week; 0 for none. */
            int weeklyOffDays,
            /* A day of the week (Monday 1 ... Sunday 7) off only in some weeks of the month; null for none. */
            Integer alternateOffDay,
            /* Which of that day's occurrences in a month are off: 1st 1, 2nd 2, 3rd 4, 4th 8, 5th 16. */
            int alternateOffWeeks) {
    }

    /**
     * {@code daysOfWeek}: Monday 1, Tuesday 2, Wednesday 4 ... Sunday 64; 0 for no weekly off. {@code alternateDay}:
     * Monday 1 ... Sunday 7, off only on the occurrences in each month that {@code alternateWeeks} names (1st 1, 2nd
     * 2, 3rd 4, 4th 8, 5th 16), such as 10 for the 2nd and 4th; null for none.
     */
    public record SaveWeeklyOffRequest(
            @NotNull @Min(0) @Max(127) Integer daysOfWeek,
            @Min(1) @Max(7) Integer alternateDay,
            @Min(0) @Max(31) Integer alternateWeeks) {
    }

    public record SaveHolidayRequest(@NotNull java.time.LocalDate date, @NotBlank @Size(max = 200) String name) {
    }

    /** A day the facility does not work, whatever its shifts say. */
    public record HolidayResponse(UUID id, java.time.LocalDate date, String name) {
    }

    public record SaveUnitRequest(@NotBlank @Size(max = 30) String code, @NotBlank @Size(max = 200) String name) {
    }

    public record UnitResponse(UUID id, String code, String name) {
    }

    /** A department named with its facility, for choosing one from several plants at once. */
    public record DepartmentOption(UUID id, UUID facilityId, String facilityCode, String code, String name) {
    }

    public record SaveShiftRequest(
            @NotBlank @Size(max = 100) String name,
            @NotNull LocalTime startsAt,
            @NotNull LocalTime endsAt,
            @Min(0) @Max(1439) Integer breakMinutes,
            @Min(1) @Max(127) Integer daysOfWeek) {
    }

    /** {@code daysOfWeek}: Monday 1, Tuesday 2, Wednesday 4 ... Sunday 64. A shift ending at or before it starts runs
     * past midnight. */
    public record ShiftResponse(UUID id, String name, LocalTime startsAt, LocalTime endsAt, int breakMinutes,
            int daysOfWeek) {
    }

    /** A facility's active shift, for working out capacity. */
    public record ShiftCalendar(UUID facilityId, String name, LocalTime startsAt, LocalTime endsAt, int breakMinutes,
            int daysOfWeek, UUID id) {

        public boolean worksOn(java.time.DayOfWeek day) {
            return (daysOfWeek & (1 << (day.getValue() - 1))) != 0;
        }

        /** Its length, past midnight if need be, less its break. */
        public long workingMinutes() {
            long length = java.time.Duration.between(startsAt, endsAt).toMinutes();
            if (length <= 0) {
                length += 24 * 60;
            }
            return Math.max(length - breakMinutes, 0);
        }
    }
}
