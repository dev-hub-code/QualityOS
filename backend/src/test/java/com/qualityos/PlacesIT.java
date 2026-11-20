package com.qualityos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

class PlacesIT extends TraceabilityFixture {

    @Test
    void anyMemberGetsStatesAndTheirCities() throws Exception {
        Session viewer = addMember(owner, "VIEWER");
        as(viewer, get("/api/v1/places/states")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(36))
                .andExpect(jsonPath("$.data[?(@.gstCode == '27')].name").value("Maharashtra"))
                .andExpect(header().string("Cache-Control", Matchers.containsString("max-age=86400")));
        as(viewer, get("/api/v1/places/states/27/cities")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasItems("Mumbai", "Pune", "Nagpur")));
        as(viewer, get("/api/v1/places/states/24/cities?q=sur")).andExpect(jsonPath("$.data",
                Matchers.contains("Surat", "Surendranagar")));
        as(viewer, get("/api/v1/places/states/99/cities")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/places/states")).andExpect(status().isUnauthorized());
    }
}
