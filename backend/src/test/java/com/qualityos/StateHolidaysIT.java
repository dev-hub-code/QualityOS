package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StateHolidaysIT extends TraceabilityFixture {

    private static final String HOLIDAYS = "/api/v1/state-holidays";

    @Test
    void facilitiesTakeTheirStatesHolidaysAndAllIndiasAndCanWorkOnThem() throws Exception {
        String pune = facilityIn("Maharashtra");
        String surat = facilityIn("Gujarat");
        String abroad = facilityIn("Bavaria");
        LocalDate day = LocalDate.now().plusDays(10);
        String shivaji = add("27", day, "Shivaji Jayanti");
        String gujarat = add("24", day.plusDays(1), "Gujarat Day");
        String republic = add("ALL", day.plusDays(2), "Republic Day");

        // Kept by state, and found by state and year.
        as(owner, postJson(HOLIDAYS, Map.of("region", "27", "date", day.toString(), "name", "Again")))
                .andExpect(status().isConflict());
        as(owner, postJson(HOLIDAYS, Map.of("region", "99", "date", day.toString(), "name", "Nowhere")))
                .andExpect(status().isUnprocessableContent());
        as(owner, postJson(HOLIDAYS, Map.of("region", "Maharashtra", "date", day.toString(), "name", "Bad")))
                .andExpect(status().isBadRequest());
        Session viewer = addMember(owner, "VIEWER");
        as(viewer, postJson(HOLIDAYS, Map.of("region", "27", "date", day.plusDays(5).toString(), "name", "Mine")))
                .andExpect(status().isForbidden());
        as(viewer, get(HOLIDAYS + "?region=27&year=" + day.getYear()))
                .andExpect(jsonPath("$.data[*].name").value(org.hamcrest.Matchers.contains("Shivaji Jayanti")))
                .andExpect(jsonPath("$.data[0].regionName").value("Maharashtra"));
        assertThat(JsonPath.<List<String>>read(body(as(viewer, get(HOLIDAYS + "?year=" + day.getYear()))),
                "$.data[*].name")).contains("Shivaji Jayanti", "Gujarat Day", "Republic Day");

        // Each facility takes its own state's and All India's; one abroad takes All India's only.
        assertThat(inheritedNames(pune)).containsExactly("Shivaji Jayanti", "Republic Day");
        assertThat(inheritedNames(surat)).containsExactly("Gujarat Day", "Republic Day");
        assertThat(inheritedNames(abroad)).containsExactly("Republic Day");
        assertThat(calendarNames(pune)).contains("Shivaji Jayanti", "Republic Day").doesNotContain("Gujarat Day");

        // Pune works on Republic Day: still listed, marked, and gone from its calendar only.
        String working = "/api/v1/facilities/" + pune + "/state-holidays/" + republic;
        as(owner, put(working).contentType("application/json").content("{\"working\":true}"))
                .andExpect(status().isNoContent());
        as(owner, get("/api/v1/facilities/" + pune))
                .andExpect(jsonPath("$.data.stateHolidays[?(@.name == 'Republic Day')].working").value(true));
        assertThat(calendarNames(pune)).contains("Shivaji Jayanti").doesNotContain("Republic Day");
        assertThat(calendarNames(surat)).contains("Republic Day");
        as(owner, put("/api/v1/facilities/" + pune + "/state-holidays/" + gujarat).contentType("application/json")
                .content("{\"working\":true}")).andExpect(status().isUnprocessableContent());
        as(viewer, put(working).contentType("application/json").content("{\"working\":false}"))
                .andExpect(status().isForbidden());
        as(owner, put(working).contentType("application/json").content("{\"working\":false}"))
                .andExpect(status().isNoContent());
        assertThat(calendarNames(pune)).contains("Republic Day");

        // Moved and renamed in place, not into another state, and removed with what facilities said of it.
        as(owner, put(HOLIDAYS + "/" + shivaji).contentType("application/json").content(json.writeValueAsString(
                Map.of("region", "27", "date", day.plusDays(3).toString(), "name", "Shiv Jayanti"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("Shiv Jayanti"));
        as(owner, put(HOLIDAYS + "/" + shivaji).contentType("application/json").content(json.writeValueAsString(
                Map.of("region", "24", "date", day.plusDays(3).toString(), "name", "Moved"))))
                .andExpect(status().isUnprocessableContent());
        as(owner, put(working).contentType("application/json").content("{\"working\":true}"))
                .andExpect(status().isNoContent());
        as(owner, delete(HOLIDAYS + "/" + republic)).andExpect(status().isNoContent());
        assertThat(inheritedNames(pune)).containsExactly("Shiv Jayanti");
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM facility_working_holidays WHERE state_holiday_id = ?::uuid", Integer.class,
                republic)).isZero();
        assertThat(asDatabaseSuperuser().queryForObject("SELECT count(*) FROM audit_logs WHERE action LIKE "
                + "'STATE_HOLIDAY_%' AND tenant_id = (SELECT tenant_id FROM state_holidays WHERE id = ?::uuid)",
                Integer.class, shivaji)).isEqualTo(8); // 3 added, worked, closed, updated, worked, removed

        // Another organisation keeps its own.
        as(register(), get(HOLIDAYS + "?year=" + day.getYear())).andExpect(jsonPath("$.data").isEmpty());
    }

    private String facilityIn(String state) throws Exception {
        return idOf(body(as(owner, postJson("/api/v1/facilities", Map.of("code", code(), "name", state + " plant",
                "state", state))).andExpect(status().isCreated())));
    }

    private String add(String region, LocalDate date, String name) throws Exception {
        return idOf(body(as(owner, postJson(HOLIDAYS, Map.of("region", region, "date", date.toString(),
                "name", name))).andExpect(status().isCreated())));
    }

    private List<String> inheritedNames(String facility) throws Exception {
        return JsonPath.read(body(as(owner, get("/api/v1/facilities/" + facility))), "$.data.stateHolidays[*].name");
    }

    private List<String> calendarNames(String facility) throws Exception {
        return JsonPath.read(body(as(owner, get("/api/v1/holiday-calendar"))),
                "$.data.holidays[?(@.facilityId == '" + facility + "' && @.kind == 'STATE_HOLIDAY')].name");
    }
}
