package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class NcrStatusLabelIT extends MasterDataFixture {

    private static final String LABELS = "/api/v1/ncr-status-labels";

    private ResultActions save(Session session, Map<String, String> labels) throws Exception {
        return as(session, put(LABELS).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("labels", labels))));
    }

    @Test
    void anOrganisationRenamesStatusesWithoutChangingTheWorkflow() throws Exception {
        Session owner = register();
        Session member = addMember(owner, "VIEWER");
        Session other = register();

        as(member, get(LABELS))
                .andExpect(jsonPath("$.data.length()").value(8))
                .andExpect(jsonPath("$.data[0].status").value("OPEN"))
                .andExpect(jsonPath("$.data[1].standardLabel").value("Under investigation"))
                .andExpect(jsonPath("$.data[1].label").isEmpty())
                .andExpect(jsonPath("$.data[5].finished").value(true));

        save(owner, Map.of("CONTAINMENT", "  Quarantine ", "PENDING_VERIFICATION", "Awaiting QA sign-off",
                        "OPEN", "Open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[2].label").value("Quarantine"))
                .andExpect(jsonPath("$.data[4].label").value("Awaiting QA sign-off"))
                // The standard name is not stored as a choice.
                .andExpect(jsonPath("$.data[0].label").isEmpty());
        as(member, get(LABELS)).andExpect(jsonPath("$.data[2].label").value("Quarantine"));
        as(other, get(LABELS)).andExpect(jsonPath("$.data[2].label").isEmpty());

        // The NCR itself keeps its status code, so every rule tied to it is unchanged; the report shows the name.
        String ncr = idOf(body(as(owner, postJson("/api/v1/ncrs", Map.of("title", "Bent bar",
                "description", "Found in stores", "severity", "MINOR"))).andExpect(status().isCreated())));
        as(owner, get("/api/v1/ncrs/" + ncr)).andExpect(jsonPath("$.data.status").value("OPEN"));
        save(owner, Map.of("OPEN", "New")).andExpect(status().isOk());
        List<List<String>> rows = JsonPath.read(body(as(owner, get("/api/v1/reports/ncr"))), "$.data.rows");
        assertThat(rows).extracting(row -> row.get(3)).containsExactly("New");

        // Saving again replaces every name: what is left out goes back to standard.
        as(owner, get(LABELS)).andExpect(jsonPath("$.data[2].label").isEmpty());
        save(member, Map.of("OPEN", "Fresh")).andExpect(status().isForbidden());
    }

    @Test
    void twoStatusesCannotLookAlikeAndNamesStayShort() throws Exception {
        Session owner = register();
        save(owner, Map.of("OPEN", "closed"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value("Two statuses cannot share the name \"Closed\"."));
        save(owner, Map.of("OPEN", "Raised", "CONTAINMENT", "RAISED"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.startsWith("Two statuses cannot share")));
        save(owner, Map.of("OPEN", "x".repeat(41)))
                .andExpect(jsonPath("$.error.message").value("A status name can be at most 40 characters."));
        save(owner, Map.of("BOGUS", "Anything")).andExpect(status().isBadRequest());
        as(owner, get(LABELS)).andExpect(jsonPath("$.data[0].label").isEmpty());
    }
}
