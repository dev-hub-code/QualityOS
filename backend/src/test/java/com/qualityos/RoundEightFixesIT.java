package com.qualityos;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What the eighth round of human testing found, kept from coming back. */
class RoundEightFixesIT extends TraceabilityFixture {

    /** A bulk syrup made in litres and released. */
    private String litres(String quantity, String plant) throws Exception {
        String syrup = idOf(body(as(owner, postJson("/api/v1/products", Map.of("code", code(), "name", "Cough syrup",
                        "productType", "FINISHED", "uomId", unitId(owner, "LTR"))))
                .andExpect(status().isCreated())));
        String batch = JsonPath.read(body(as(owner, postJson("/api/v1/production-batches", Map.of(
                        "productId", syrup, "facilityId", plant, "plannedQuantity", quantity)))
                .andExpect(status().isCreated())), "$.data.batch.id");
        consumeLot(batch, releasedLot("10").id(), "10").andExpect(status().isOk());
        complete(batch, quantity).andExpect(status().isOk());
        inspectBatch(batch, "FQC", "20.0", "ACCEPT");
        return batch;
    }

    @Test
    void dispatchedAndReturnedQuantitiesSayTheirUnitAndAreNotAddedAcrossUnits() throws Exception {
        String kits = releasedBatch(releasedLot("10").id(), "10", "10");
        String syrup = litres("100", JsonPath.read(body(as(owner, get("/api/v1/production-batches/" + kits))),
                "$.data.batch.facilityId"));
        String response = body(as(owner, postJson("/api/v1/dispatches", Map.of(
                        "customerId", customer(), "dispatchedOn", TODAY,
                        "items", List.of(
                                Map.of("productionBatchId", syrup, "quantity", "40.5", "serialNumbers", List.of()),
                                Map.of("productionBatchId", kits, "quantity", "5", "serialNumbers", List.of())))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[*].unit", containsInAnyOrder("LTR", "PCS"))));
        String dispatch = idOf(response);
        String syrupLine = JsonPath.<List<String>>read(response, "$.data.lines[?(@.unit == 'LTR')].id").getFirst();
        String kitLine = JsonPath.<List<String>>read(response, "$.data.lines[?(@.unit == 'PCS')].id").getFirst();
        ship(dispatch).andExpect(status().isOk());

        as(owner, postJson("/api/v1/dispatches/" + dispatch + "/reversals", Map.of(
                        "returnReference", "RMA-1", "reversalDate", TODAY, "reason", "Crystals in the drum",
                        "items", List.of(
                                Map.of("dispatchItemId", syrupLine, "quantity", "10.5", "serialNumbers", List.of()),
                                Map.of("dispatchItemId", kitLine, "quantity", "2", "serialNumbers", List.of())))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items[*].unit", containsInAnyOrder("LTR", "PCS")));

        as(owner, get("/api/v1/dispatches/" + dispatch + "/reversal-summary"))
                .andExpect(jsonPath("$.data.labels.dispatched").value(containsString("40.5 LTR")))
                .andExpect(jsonPath("$.data.labels.dispatched").value(containsString("5 PCS")))
                .andExpect(jsonPath("$.data.labels.reversed").value(containsString("10.5 LTR")))
                .andExpect(jsonPath("$.data.labels.reversed").value(containsString("2 PCS")))
                .andExpect(jsonPath("$.data.labels.remaining").value(containsString("30 LTR")))
                .andExpect(jsonPath("$.data.labels.remaining").value(containsString("3 PCS")));
    }
}
