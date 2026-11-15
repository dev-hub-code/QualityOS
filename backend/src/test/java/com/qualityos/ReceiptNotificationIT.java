package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Received material that must be inspected is announced to those who start inspections where it arrived. */
class ReceiptNotificationIT extends TraceabilityFixture {

    private List<String> titles(Session session) throws Exception {
        return JsonPath.read(body(as(session, get("/api/v1/notifications")).andExpect(status().isOk())),
                "$.data.items[?(@.type == 'MATERIAL_AWAITING_INSPECTION')].title");
    }

    @Test
    void inspectorsAtTheFacilityHearOfLotsToInspect() throws Exception {
        Session inspector = addMember(owner, "QUALITY_INSPECTOR");
        Session elsewhere = addMember(owner, "QUALITY_INSPECTOR");
        Session viewer = addMember(owner, "VIEWER");
        limit(elsewhere, facility(owner));

        Lot lot = lot(material(owner, true), "25");
        assertThat(titles(inspector)).singleElement().asString().endsWith(": 1 lot(s) to inspect");
        String body = body(as(inspector, get("/api/v1/notifications")));
        assertThat(body).contains(lot.batch()).contains("\"link\":\"/inspections\"");
        assertThat(titles(elsewhere)).isEmpty();
        assertThat(titles(viewer)).isEmpty();
        assertThat(titles(owner)).isEmpty();

        // Material released on arrival needs nobody.
        releasedLot("5");
        assertThat(titles(inspector)).hasSize(1);
    }
}
