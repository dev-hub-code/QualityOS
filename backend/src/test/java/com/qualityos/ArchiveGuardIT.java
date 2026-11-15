package com.qualityos;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** A product, material or facility that production still relies on cannot be archived until the use ends. */
class ArchiveGuardIT extends TraceabilityFixture {

    private static final String API = "/api/v1/manufacturing";

    private ResultActions send(String path, Object body) throws Exception {
        return as(owner, postJson(API + path, body));
    }

    private ResultActions archive(String path) throws Exception {
        return as(owner, delete("/api/v1" + path));
    }

    @Test
    void masterDataInUseByProductionStaysUntilTheUseEnds() throws Exception {
        String facility = facility(owner);
        String material = material(owner, false, "KG");
        String product = idOf(body(as(owner, postJson("/api/v1/products", Map.of("code", code(), "name", "Shaft",
                        "productType", "FINISHED", "uomId", unitId(owner, "PCS"))))
                .andExpect(status().isCreated())));
        String bom = JsonPath.read(body(send("/boms", Map.of("productId", product))
                .andExpect(status().isCreated())), "$.data.revision.id");
        send("/boms/" + bom + "/lines", Map.of("materialId", material, "quantity", "1",
                "uomId", unitId(owner, "KG"))).andExpect(status().isOk());
        String centre = idOf(body(send("/work-centers", Map.of("code", "WC-" + code(), "name", "Turning",
                "facilityId", facility)).andExpect(status().isCreated())));
        String routing = JsonPath.read(body(send("/routings", Map.of("productId", product))
                .andExpect(status().isCreated())), "$.data.revision.id");
        send("/routings/" + routing + "/operations", Map.of("sequence", 10, "name", "Turn",
                "workCenterId", centre, "setupMinutes", "0", "runMinutes", "60")).andExpect(status().isOk());
        for (String path : new String[] {"/boms/" + bom, "/routings/" + routing}) {
            send(path + "/submit", Map.of()).andExpect(status().isOk());
            as(manager, postJson(API + path + "/approve", Map.of())).andExpect(status().isOk());
        }
        String order = JsonPath.read(body(send("/work-orders", Map.of("productId", product, "facilityId", facility,
                "quantity", "5", "plannedStart", LocalDate.now().toString(),
                "plannedEnd", LocalDate.now().plusDays(3).toString())).andExpect(status().isCreated())),
                "$.data.workOrder.id");

        archive("/products/" + product)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(allOf(containsString("This product is still in use"),
                        containsString("1 open work order"), containsString("1 approved BOM or recipe"),
                        containsString("1 approved routing"))));
        archive("/materials/" + material)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("1 approved BOM or recipe")));
        archive("/facilities/" + facility)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(allOf(containsString("1 work centre in use"),
                        containsString("1 open work order"))));

        // Ending each use lets the record go.
        send("/work-orders/" + order + "/cancel", Map.of("reason", "Not needed")).andExpect(status().isOk());
        for (String path : new String[] {"/boms/" + bom, "/routings/" + routing}) {
            as(manager, postJson(API + path + "/retire", Map.of("reason", "Product withdrawn")))
                    .andExpect(status().isOk());
        }
        as(owner, delete(API + "/work-centers/" + centre)).andExpect(status().isNoContent());
        archive("/products/" + product).andExpect(status().isNoContent());
        archive("/materials/" + material).andExpect(status().isNoContent());
        archive("/facilities/" + facility).andExpect(status().isNoContent());
    }
}
