package com.qualityos;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** What the sixth round of human testing found, kept from coming back. */
class RoundSixFixesIT extends TraceabilityFixture {

    @Test
    void aRejectedSupplierIsReportedToWhoeverSubmittedIt() throws Exception {
        Session reviewer = addMember(owner, "SUPPLIER_MANAGER");
        String supplier = draftSupplier(owner);
        as(owner, postJson("/api/v1/suppliers/" + supplier + "/submit", Map.of())).andExpect(status().isOk());
        as(reviewer, postJson("/api/v1/suppliers/" + supplier + "/reject", Map.of("note", "No FSSAI licence")))
                .andExpect(status().isOk());
        as(owner, get("/api/v1/notifications"))
                .andExpect(jsonPath("$.data.items[*].type", hasItem("SUPPLIER_REJECTED")))
                .andExpect(jsonPath("$.data.items[?(@.type == 'SUPPLIER_REJECTED')].body",
                        hasItem("No FSSAI licence")));
    }

    @Test
    void aBatchWithNoTemplateToInspectItSaysWhichKindToPublish() throws Exception {
        String batch = startBatch(product());
        as(owner, postJson("/api/v1/inspections", Map.of("productionBatchId", batch)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("Publish an in-process (IPQC) template for")));
    }
}
