package com.qualityos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class OrganizationSettingsIT extends AbstractIntegrationTest {

    private ResultActions save(Session session, boolean singlePersonReview, Long version) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("singlePersonReview", singlePersonReview);
        body.put("capaRequiredFrom", "MAJOR");
        body.put("effectivenessRequiredFrom", "MAJOR");
        body.put("version", version);
        return as(session, put("/api/v1/organizations/current/settings")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    @Test
    void settingsStartAtTheirDefaultsAndApplyOnlyToASoleMember() throws Exception {
        Session owner = register();
        as(owner, get("/api/v1/organizations/current/settings"))
                .andExpect(jsonPath("$.data.singlePersonReview").value(false))
                .andExpect(jsonPath("$.data.selfReviewInEffect").value(false))
                .andExpect(jsonPath("$.data.activeMembers").value(1))
                .andExpect(jsonPath("$.data.capaRequiredFrom").value("MAJOR"))
                .andExpect(jsonPath("$.data.effectivenessRequiredFrom").value("MAJOR"))
                .andExpect(jsonPath("$.data.version").doesNotExist());

        save(owner, true, null)
                .andExpect(jsonPath("$.data.singlePersonReview").value(true))
                .andExpect(jsonPath("$.data.selfReviewInEffect").value(true))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    void everyRuleMustBeSentAndKnown() throws Exception {
        Session owner = register();
        as(owner, put("/api/v1/organizations/current/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"singlePersonReview\":false,\"capaRequiredFrom\":\"SOMETIMES\","
                                + "\"effectivenessRequiredFrom\":\"MAJOR\"}"))
                .andExpect(status().isBadRequest());
        as(owner, put("/api/v1/organizations/current/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"singlePersonReview\":false}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aStaleSaveIsRefused() throws Exception {
        Session owner = register();
        save(owner, true, null).andExpect(status().isOk());
        save(owner, false, null).andExpect(status().isConflict());
        save(owner, false, 0L).andExpect(jsonPath("$.data.singlePersonReview").value(false));
    }

    @Test
    void anyMemberMayReadButOnlyAnAdministratorMayChange() throws Exception {
        Session owner = register();
        Session viewer = addMember(owner, "VIEWER");
        as(viewer, get("/api/v1/organizations/current/settings")).andExpect(status().isOk());
        save(viewer, true, null).andExpect(status().isForbidden());
    }

    @Test
    void eachOrganisationHasItsOwnSettings() throws Exception {
        Session first = register();
        Session second = register();
        save(first, true, null).andExpect(status().isOk());
        as(second, get("/api/v1/organizations/current/settings"))
                .andExpect(jsonPath("$.data.singlePersonReview").value(false));
        save(second, true, null).andExpect(jsonPath("$.data.version").value(0));
    }
}
