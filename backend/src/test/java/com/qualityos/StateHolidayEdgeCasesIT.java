package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The holiday calendar where it is easy to get wrong: moving state, leap days, the turn of the year, two admins. */
class StateHolidayEdgeCasesIT extends TraceabilityFixture {

    private static final String HOLIDAYS = "/api/v1/state-holidays";

    @Test
    void aFacilityMovingStateTakesTheNewStatesHolidaysAndForgetsWhatItSaidOfTheOld() throws Exception {
        String plant = facilityIn("Maharashtra");
        LocalDate day = LocalDate.now().plusDays(20);
        String shivaji = add("27", day, "Shivaji Jayanti");
        add("24", day.plusDays(1), "Gujarat Day");
        String republic = add("ALL", day.plusDays(2), "Republic Day");
        working(plant, shivaji, true);
        working(plant, republic, true);

        moveTo(plant, "Gujarat");
        assertThat(inherited(plant, "name")).containsExactly("Gujarat Day", "Republic Day");
        // All India still applies, so the plant still works on Republic Day; Shivaji Jayanti is forgotten.
        assertThat(inherited(plant, "working")).containsExactly(false, true);
        assertThat(calendar(plant, day.minusDays(1), day.plusDays(5))).containsExactly("Gujarat Day");
        assertThat(workingRows(plant)).isEqualTo(1);
        // Gujarat's holidays only: a Maharashtra one cannot be worked on now.
        as(owner, put("/api/v1/facilities/" + plant + "/state-holidays/" + shivaji).contentType("application/json")
                .content("{\"working\":true}")).andExpect(status().isUnprocessableContent());

        // Back in Maharashtra, written loosely, it closes on Shivaji Jayanti again like any plant there.
        moveTo(plant, "  maharashtra ");
        assertThat(inherited(plant, "name")).containsExactly("Shivaji Jayanti", "Republic Day");
        assertThat(inherited(plant, "working")).containsExactly(false, true);
        assertThat(calendar(plant, day.minusDays(1), day.plusDays(5))).containsExactly("Shivaji Jayanti");

        // A state not in the list keeps All India's only, and what it said of them.
        moveTo(plant, "Bavaria");
        assertThat(inherited(plant, "name")).containsExactly("Republic Day");
        assertThat(inherited(plant, "working")).containsExactly(true);
    }

    @Test
    void removingAHolidayAPlantWorksOnLeavesNothingBehind() throws Exception {
        String plant = facilityIn("Maharashtra");
        LocalDate day = LocalDate.now().plusDays(30);
        String gudi = add("27", day, "Gudi Padwa");
        working(plant, gudi, true);
        as(owner, delete(HOLIDAYS + "/" + gudi)).andExpect(status().isNoContent());
        assertThat(workingRows(plant)).isZero();
        assertThat(inherited(plant, "name")).isEmpty();
        // Added again, it is a new holiday: the plant closes on it until it says otherwise.
        add("27", day, "Gudi Padwa");
        assertThat(inherited(plant, "working")).containsExactly(false);
        assertThat(calendar(plant, day, day)).containsExactly("Gudi Padwa");
    }

    @Test
    void leapDaysAndTheTurnOfTheYear() throws Exception {
        String plant = facilityIn("Maharashtra");
        add("ALL", LocalDate.of(2028, 2, 29), "Leap day shutdown");
        add("27", LocalDate.of(2027, 12, 31), "Year end");
        add("27", LocalDate.of(2028, 1, 1), "New Year");

        assertThat(listed(2028)).containsExactly("New Year", "Leap day shutdown");
        assertThat(listed(2027)).containsExactly("Year end");
        // A range across the turn of the year takes both years' holidays.
        assertThat(calendar(plant, LocalDate.of(2027, 12, 25), LocalDate.of(2028, 1, 5)))
                .containsExactly("Year end", "New Year");
        assertThat(calendar(plant, LocalDate.of(2028, 2, 28), LocalDate.of(2028, 3, 1)))
                .containsExactly("Leap day shutdown");
        // A holiday can be moved onto the leap day, and is refused on a day that does not exist.
        String moved = add("27", LocalDate.of(2028, 2, 27), "Moved");
        as(owner, put(HOLIDAYS + "/" + moved).contentType("application/json").content(json.writeValueAsString(
                Map.of("region", "27", "date", "2028-02-29", "name", "Moved")))).andExpect(status().isOk());
        as(owner, postJson(HOLIDAYS, Map.of("region", "27", "date", "2027-02-29", "name", "No such day")))
                .andExpect(status().isBadRequest());

        // Second and fourth Saturdays, and every fifth one, in a leap February and a five-Saturday January.
        as(owner, put("/api/v1/facilities/" + plant + "/weekly-off").contentType("application/json")
                .content(json.writeValueAsString(Map.of("daysOfWeek", 0, "alternateDay", 6, "alternateWeeks", 26))))
                .andExpect(status().is2xxSuccessful());
        String feb = body(as(owner, get("/api/v1/holiday-calendar?from=2028-01-01&to=2028-02-29")));
        assertThat(JsonPath.<List<String>>read(feb, "$.data.holidays[?(@.facilityId == '" + plant
                + "' && @.kind == 'ALTERNATE_OFF')].date"))
                .containsExactly("2028-01-08", "2028-01-22", "2028-01-29", "2028-02-12", "2028-02-26");
    }

    @Test
    void twoAdminsAtOnceNeverMakeTwoOfAHolidayOrAnError() throws Exception {
        Session other = addMember(owner, "ORGANISATION_ADMIN");
        String plant = facilityIn("Maharashtra");
        LocalDate day = LocalDate.now().plusDays(40);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // The same holiday added by both: one is added, the other is told it exists.
            for (int round = 0; round < 5; round++) {
                LocalDate on = day.plusDays(round);
                List<Integer> codes = together(pool,
                        postJson(HOLIDAYS, Map.of("region", "27", "date", on.toString(), "name", "Same")), owner,
                        postJson(HOLIDAYS, Map.of("region", "27", "date", on.toString(), "name", "Same")), other);
                assertThat(codes).containsExactlyInAnyOrder(201, 409);
            }
            assertThat(asDatabaseSuperuser().queryForObject("""
                    SELECT count(*) FROM state_holidays WHERE tenant_id = ? AND name = 'Same'""", Integer.class,
                    owner.tenantId())).isEqualTo(5);

            // Two holidays moved onto the same day at once: one moves, the other is refused, never a 500.
            for (int round = 0; round < 5; round++) {
                LocalDate target = day.plusDays(20 + round);
                String first = add("27", day.plusDays(10 + 2 * round), "First " + round);
                String second = add("27", day.plusDays(11 + 2 * round), "Second " + round);
                List<Integer> codes = together(pool,
                        put(HOLIDAYS + "/" + first).contentType("application/json").content(json.writeValueAsString(
                                Map.of("region", "27", "date", target.toString(), "name", "First " + round))), owner,
                        put(HOLIDAYS + "/" + second).contentType("application/json").content(json.writeValueAsString(
                                Map.of("region", "27", "date", target.toString(), "name", "Second " + round))), other);
                assertThat(codes).containsExactlyInAnyOrder(200, 409);
            }

            // Both mark the plant as working on a holiday: one mark, one audit entry.
            String holiday = add("27", day.plusDays(60), "Worked twice");
            List<Integer> codes = together(pool,
                    put("/api/v1/facilities/" + plant + "/state-holidays/" + holiday).contentType("application/json")
                            .content("{\"working\":true}"), owner,
                    put("/api/v1/facilities/" + plant + "/state-holidays/" + holiday).contentType("application/json")
                            .content("{\"working\":true}"), other);
            assertThat(codes).containsOnly(204);
            assertThat(workingRows(plant)).isEqualTo(1);
            assertThat(asDatabaseSuperuser().queryForObject("""
                    SELECT count(*) FROM audit_logs WHERE action = 'STATE_HOLIDAY_WORKED' AND entity_id::text = ?""",
                    Integer.class, plant)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Sends both requests at the same moment and returns their statuses. */
    private List<Integer> together(ExecutorService pool, MockHttpServletRequestBuilder first, Session firstBy, MockHttpServletRequestBuilder second,
            Session secondBy) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Future<MockHttpServletResponse>> sent = new ArrayList<>();
        for (Map.Entry<MockHttpServletRequestBuilder, Session> call : List.of(Map.entry(first, firstBy), Map.entry(second, secondBy))) {
            Callable<MockHttpServletResponse> send = () -> {
                barrier.await();
                return as(call.getValue(), call.getKey()).andReturn().getResponse();
            };
            sent.add(pool.submit(send));
        }
        List<Integer> codes = new ArrayList<>();
        for (Future<MockHttpServletResponse> response : sent) {
            codes.add(response.get().getStatus());
        }
        return codes;
    }

    private String facilityIn(String state) throws Exception {
        return idOf(body(as(owner, postJson("/api/v1/facilities", Map.of("code", code(), "name", "Plant " + code(),
                "state", state))).andExpect(status().isCreated())));
    }

    private void moveTo(String facility, String state) throws Exception {
        String current = body(as(owner, get("/api/v1/facilities/" + facility)));
        Map<String, Object> request = new HashMap<>();
        request.put("code", JsonPath.read(current, "$.data.facility.code"));
        request.put("name", JsonPath.read(current, "$.data.facility.name"));
        request.put("state", state);
        request.put("version", JsonPath.read(current, "$.data.facility.version"));
        as(owner, put("/api/v1/facilities/" + facility).contentType("application/json")
                .content(json.writeValueAsString(request))).andExpect(status().isOk());
    }

    private String add(String region, LocalDate date, String name) throws Exception {
        return idOf(body(as(owner, postJson(HOLIDAYS, Map.of("region", region, "date", date.toString(),
                "name", name))).andExpect(status().isCreated())));
    }

    private void working(String facility, String holiday, boolean working) throws Exception {
        as(owner, put("/api/v1/facilities/" + facility + "/state-holidays/" + holiday)
                .contentType("application/json").content("{\"working\":" + working + "}"))
                .andExpect(status().isNoContent());
    }

    private <T> List<T> inherited(String facility, String field) throws Exception {
        return JsonPath.read(body(as(owner, get("/api/v1/facilities/" + facility))),
                "$.data.stateHolidays[*]." + field);
    }

    private List<String> listed(int year) throws Exception {
        return JsonPath.read(body(as(owner, get(HOLIDAYS + "?year=" + year))), "$.data[*].name");
    }

    /** The state holidays the plant closes on between the two days, as the calendar shows them. */
    private List<String> calendar(String facility, LocalDate from, LocalDate to) throws Exception {
        return JsonPath.read(body(as(owner, get("/api/v1/holiday-calendar?from=" + from + "&to=" + to))),
                "$.data.holidays[?(@.facilityId == '" + facility + "' && @.kind == 'STATE_HOLIDAY')].name");
    }

    private int workingRows(String facility) {
        return asDatabaseSuperuser().queryForObject("SELECT count(*) FROM facility_working_holidays WHERE "
                + "facility_id = ?::uuid", Integer.class, facility);
    }
}
