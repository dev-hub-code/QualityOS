package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** An organisation reads its own audit trail, by name, and nobody reads another's. */
class AuditLogIT extends MasterDataFixture {

    @Test
    void membersSeeWhoDidWhatAndCanNarrowIt() throws Exception {
        Session owner = register();
        String facility = facility(owner);

        String page = body(as(owner, get("/api/v1/audit-logs").param("action", "FACILITY_CREATED"))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(page, "$.data.totalItems")).isEqualTo(1);
        assertThat(JsonPath.<String>read(page, "$.data.items[0].actor")).isEqualTo("Test User");
        assertThat(JsonPath.<String>read(page, "$.data.items[0].entityType")).isEqualTo("Facility");
        assertThat(JsonPath.<String>read(page, "$.data.items[0].entityId")).isEqualTo(facility);

        // The search matches the target's ID, and the day filter is inclusive at both ends.
        String today = LocalDate.now().toString();
        as(owner, get("/api/v1/audit-logs").param("q", facility.substring(0, 8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].entityId").value(org.hamcrest.Matchers.hasItem(facility)));
        as(owner, get("/api/v1/audit-logs").param("from", today).param("to", today).param("action", "FACILITY_CREATED"))
                .andExpect(jsonPath("$.data.totalItems").value(1));
        as(owner, get("/api/v1/audit-logs").param("from", LocalDate.now().plusDays(1).toString()))
                .andExpect(jsonPath("$.data.totalItems").value(0));
        as(owner, get("/api/v1/audit-logs").param("action", "NO_SUCH_ACTION"))
                .andExpect(jsonPath("$.data.totalItems").value(0));
    }

    @Test
    void anotherOrganisationAndAMemberWithoutThePermissionSeeNothing() throws Exception {
        Session owner = register();
        facility(owner);
        Session other = register();
        String theirs = body(as(other, get("/api/v1/audit-logs").param("action", "FACILITY_CREATED")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(theirs, "$.data.items[*].id")).isEmpty();

        Session viewer = addMember(owner, "VIEWER");
        as(viewer, get("/api/v1/audit-logs")).andExpect(status().isForbidden());
    }

    @Test
    void membersAccountEventsShowFromTheDayTheyJoined() throws Exception {
        Session owner = register();
        Session member = addMember(owner, "VIEWER");
        Session elsewhere = register();
        mvc.perform(postJson("/api/v1/auth/password-reset/request", java.util.Map.of("email", elsewhere.email())))
                .andExpect(status().is2xxSuccessful());
        addExistingAccount(owner, elsewhere, "VIEWER");
        mvc.perform(postJson("/api/v1/auth/password-reset/request", java.util.Map.of("email", member.email())))
                .andExpect(status().is2xxSuccessful());

        // The member's request shows; the other account's, made before it joined, does not.
        String page = body(as(owner, get("/api/v1/audit-logs").param("action", "PASSWORD_RESET_REQUESTED"))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(page, "$.data.items[*].entityId"))
                .containsExactly(member.userId().toString());

        // Another organisation the member does not belong to sees none of it.
        Session stranger = register();
        as(stranger, get("/api/v1/audit-logs").param("action", "PASSWORD_RESET_REQUESTED"))
                .andExpect(jsonPath("$.data.totalItems").value(0));
    }
}
