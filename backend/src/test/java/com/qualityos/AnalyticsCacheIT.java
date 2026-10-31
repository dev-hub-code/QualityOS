package com.qualityos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AnalyticsCacheIT extends TraceabilityFixture {

    private static final String OVERVIEW = "/api/v1/analytics/overview";

    private void raiseNcr(String facilityId) throws Exception {
        as(owner, postJson("/api/v1/ncrs", Map.of("title", "Scratches", "description", "Seen on the line",
                        "severity", "MINOR", "facilityId", facilityId)))
                .andExpect(status().isCreated());
    }

    private void expectOpenNcrs(Session session, int open) throws Exception {
        as(session, get(OVERVIEW)).andExpect(jsonPath("$.data.kpis.openNcrs").value(open));
    }

    /** Changes the data behind the application's back, so only a stored answer can still show the old figure. */
    private void cancelEveryNcrUnseen() {
        asDatabaseSuperuser().update("UPDATE non_conformances SET status = 'CANCELLED' WHERE tenant_id = ?", owner.tenantId());
    }

    @Test
    void repeatedQuestionsAreAnsweredFromTheCacheAndARecordedChangeClearsIt() throws Exception {
        raiseNcr(facility(owner));
        expectOpenNcrs(owner, 1);

        cancelEveryNcrUnseen();
        expectOpenNcrs(owner, 1);

        String facility = facility(owner);
        raiseNcr(facility);
        raiseNcr(facility);
        expectOpenNcrs(owner, 2);
    }

    @Test
    void anotherOrganisationNeverReceivesStoredFigures() throws Exception {
        raiseNcr(facility(owner));
        expectOpenNcrs(owner, 1);

        expectOpenNcrs(register(), 0);
    }

    @Test
    void aLimitedMemberNeverReceivesFiguresStoredForAWiderView() throws Exception {
        raiseNcr(facility(owner));
        String elsewhere = facility(owner);
        Session limited = addMember(owner, "QUALITY_MANAGER");
        limit(limited, elsewhere);

        expectOpenNcrs(owner, 1);
        expectOpenNcrs(limited, 0);
        expectOpenNcrs(owner, 1);
    }

    @Test
    void aLimitTakesEffectAtOnceOverStoredFigures() throws Exception {
        raiseNcr(facility(owner));
        Session member = addMember(owner, "QUALITY_MANAGER");
        expectOpenNcrs(member, 1);

        limit(member, facility(owner));
        expectOpenNcrs(member, 0);
    }

    @Test
    void storedFiguresDoNotAnswerSomeoneWithoutThePermission() throws Exception {
        raiseNcr(facility(owner));
        expectOpenNcrs(owner, 1);

        as(addMember(owner, "QUALITY_INSPECTOR"), get(OVERVIEW)).andExpect(status().isForbidden());
    }

    @Test
    void aFacilityOutsideTheLimitIsRefusedEvenWhenItsFiguresAreStored() throws Exception {
        String here = facility(owner);
        raiseNcr(here);
        as(owner, get(OVERVIEW + "?facilityId=" + here)).andExpect(jsonPath("$.data.kpis.openNcrs").value(1));

        Session limited = addMember(owner, "QUALITY_MANAGER");
        limit(limited, facility(owner));
        as(limited, get(OVERVIEW + "?facilityId=" + here)).andExpect(status().isNotFound());
    }
}
