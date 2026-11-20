package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HolidayCalendarIT extends TraceabilityFixture {

    private static final String CALENDAR = "/api/v1/holiday-calendar";

    @Test
    void everyMemberSeesUpcomingHolidaysOfTheFacilitiesTheyCanOpen() throws Exception {
        String here = facility(owner);
        String there = facility(owner);
        LocalDate soon = LocalDate.now().plusDays(5);
        holiday(here, soon, "Diwali");
        holiday(there, soon.plusDays(1), "Founders' day");
        holiday(here, LocalDate.now().minusDays(3), "Already past");
        holiday(here, LocalDate.now().plusDays(200), "Far off");
        as(owner, put("/api/v1/facilities/" + here + "/weekly-off").contentType("application/json")
                .content(json.writeValueAsString(Map.of("daysOfWeek", 64, "alternateDay", 6, "alternateWeeks", 10))))
                .andExpect(status().isNoContent());

        // Anyone in the organisation, whatever their role, without facility permissions.
        Session inspector = addMember(owner, "QUALITY_INSPECTOR");
        String calendar = body(as(inspector, get(CALENDAR)).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(calendar, "$.data.holidays[?(@.kind == 'HOLIDAY')].name"))
                .containsExactly("Diwali", "Founders' day");

        // Each Sunday and each 2nd and 4th Saturday at the first facility is listed with its date, except a day that
        // is already a named holiday.
        LocalDate today = LocalDate.now();
        List<String> sundays = today.datesUntil(today.plusDays(90))
                .filter(day -> day.getDayOfWeek() == java.time.DayOfWeek.SUNDAY && !day.equals(soon))
                .map(LocalDate::toString).toList();
        List<String> saturdays = today.datesUntil(today.plusDays(90))
                .filter(day -> day.getDayOfWeek() == java.time.DayOfWeek.SATURDAY && !day.equals(soon))
                .filter(day -> List.of(1, 3).contains((day.getDayOfMonth() - 1) / 7))
                .map(LocalDate::toString).toList();
        assertThat(JsonPath.<List<String>>read(calendar, "$.data.holidays[?(@.kind == 'WEEKLY_OFF')].date"))
                .containsExactlyElementsOf(sundays);
        assertThat(JsonPath.<List<String>>read(calendar, "$.data.holidays[?(@.kind == 'ALTERNATE_OFF')].date"))
                .containsExactlyElementsOf(saturdays);
        assertThat(JsonPath.<List<String>>read(calendar,
                "$.data.holidays[?(@.kind == 'WEEKLY_OFF')].facilityId")).containsOnly(here);
        List<String> dates = JsonPath.read(calendar, "$.data.holidays[*].date");
        assertThat(dates).isSorted();
        assertThat(JsonPath.<List<Integer>>read(calendar, "$.data.facilities[?(@.id == '" + here + "')].weeklyOffDays"))
                .containsExactly(64);
        assertThat(JsonPath.<List<Integer>>read(calendar,
                "$.data.facilities[?(@.id == '" + here + "')].alternateOffWeeks")).containsExactly(10);

        // A longer period reaches further.
        String year = body(as(inspector, get(CALENDAR + "?from=" + LocalDate.now() + "&to="
                + LocalDate.now().plusDays(300))));
        assertThat(JsonPath.<List<String>>read(year, "$.data.holidays[*].name")).contains("Far off");
        as(inspector, get(CALENDAR + "?from=" + LocalDate.now() + "&to=" + LocalDate.now().plusDays(400)))
                .andExpect(status().isUnprocessableContent());

        // A member limited to one facility sees only that one.
        limit(manager, there);
        String limited = body(as(manager, get(CALENDAR)));
        assertThat(JsonPath.<List<String>>read(limited, "$.data.holidays[*].name")).containsExactly("Founders' day");
        assertThat(JsonPath.<List<String>>read(limited, "$.data.facilities[*].id")).containsExactly(there);

        // Another organisation sees none of it.
        as(register(), get(CALENDAR)).andExpect(jsonPath("$.data.holidays").isEmpty());
    }

    private void holiday(String facility, LocalDate date, String name) throws Exception {
        as(owner, postJson("/api/v1/facilities/" + facility + "/holidays", Map.of("date", date.toString(),
                "name", name))).andExpect(status().isCreated());
    }
}
