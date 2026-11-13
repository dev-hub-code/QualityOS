package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

class UnitConversionIT extends TraceabilityFixture {

    private ResultActions consume(String batch, String sourceKey, String source, String quantity, String unit)
            throws Exception {
        Map<String, Object> request = new HashMap<>(Map.of(sourceKey, source, "quantity", quantity));
        if (unit != null) {
            request.put("uomId", unit.contains("-") ? unit : unitId(owner, unit));
        }
        return as(owner, postJson("/api/v1/production-batches/" + batch + "/consume", request));
    }

    private static BigDecimal decimal(String body, String path) {
        return new BigDecimal(JsonPath.<String>read(body, path));
    }

    @Test
    void sharedUnitsSayHowTheyConvert() throws Exception {
        String units = body(as(owner, get("/api/v1/units")));
        assertThat(new BigDecimal(JsonPath.<List<String>>read(units, "$.data[?(@.code == 'GM')].toBase").getFirst()))
                .isEqualByComparingTo("0.001");
        assertThat(new BigDecimal(JsonPath.<List<String>>read(units, "$.data[?(@.code == 'KG')].toBase").getFirst()))
                .isEqualByComparingTo("1");
        assertThat(JsonPath.<List<Object>>read(units, "$.data[?(@.code == 'HRC')].toBase")).containsOnlyNulls();
    }

    @Test
    void aLotInKilogramsIsDrawnOnInGramsAndKeepsWhatWasTyped() throws Exception {
        Lot lot = releasedLot("10");
        String batch = startBatch(product());

        String detail = body(consume(batch, "receiptItemId", lot.id(), "2500", "GM").andExpect(status().isOk()));
        assertThat(decimal(detail, "$.data.consumptions[0].quantity")).isEqualByComparingTo("2.5");
        assertThat(JsonPath.<String>read(detail, "$.data.consumptions[0].uomId")).isEqualTo(unitId(owner, "KG"));
        assertThat(decimal(detail, "$.data.consumptions[0].enteredQuantity")).isEqualByComparingTo("2500");
        assertThat(JsonPath.<String>read(detail, "$.data.consumptions[0].enteredUomId")).isEqualTo(unitId(owner, "GM"));

        // 7.5 kg are left, whichever unit asks for them.
        consume(batch, "receiptItemId", lot.id(), "7501", "GM")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("only")));
        consume(batch, "receiptItemId", lot.id(), "7500", "GM").andExpect(status().isOk());
    }

    @Test
    void aQuantityWithoutAUnitIsInTheSourcesOwn() throws Exception {
        Lot lot = releasedLot("10");
        String batch = startBatch(product());
        String detail = body(consumeLot(batch, lot.id(), "4").andExpect(status().isOk()));
        assertThat(decimal(detail, "$.data.consumptions[0].quantity")).isEqualByComparingTo("4");
        assertThat(JsonPath.<Object>read(detail, "$.data.consumptions[0].enteredUomId")).isNull();
    }

    @Test
    void aQuantityOfAnotherKindOrTooSmallToKeepIsRefused() throws Exception {
        Lot lot = releasedLot("10");
        String batch = startBatch(product());

        consume(batch, "receiptItemId", lot.id(), "3", "PCS")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("A quantity in PCS cannot be converted to KG."));
        consume(batch, "receiptItemId", lot.id(), "0.0001", "GM")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("too little")));
        consume(batch, "receiptItemId", lot.id(), "1", "00000000-0000-0000-0000-000000000000")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void aReversalReturnsWhatWasTakenInTheLotsUnit() throws Exception {
        Lot lot = releasedLot("10");
        String batch = startBatch(product());
        String detail = body(consume(batch, "receiptItemId", lot.id(), "10000", "GM").andExpect(status().isOk()));
        String entry = JsonPath.read(detail, "$.data.consumptions[0].id");

        as(owner, postJson("/api/v1/production-batches/" + batch + "/consumptions/" + entry + "/reverse",
                        Map.of("reason", "Wrong lot")))
                .andExpect(status().isOk());
        consumeLot(batch, lot.id(), "10").andExpect(status().isOk());
    }

    @Test
    void anOrganisationsOwnUnitConvertsWithTheShared() throws Exception {
        as(owner, postJson("/api/v1/units", Map.of("code", "TON", "name", "Tonne", "dimension", "Mass",
                        "toBase", "1000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.system").value(false));
        Lot lot = releasedLot("10");
        String batch = startBatch(product());

        String detail = body(consume(batch, "receiptItemId", lot.id(), "0.002", "TON").andExpect(status().isOk()));
        assertThat(decimal(detail, "$.data.consumptions[0].quantity")).isEqualByComparingTo("2");
    }

    @Test
    void aBatchIsDrawnOnInItsProductsUnit() throws Exception {
        String component = releasedBatch(releasedLot("10").id(), "10", "30");
        String assembly = startBatch(product());

        consume(assembly, "parentBatchId", component, "5", "KG").andExpect(status().isUnprocessableContent());
        String detail = body(consume(assembly, "parentBatchId", component, "5", "PCS").andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(detail, "$.data.consumptions[0].uomId")).isEqualTo(unitId(owner, "PCS"));
        assertThat(JsonPath.<String>read(detail, "$.data.batch.uomId")).isEqualTo(unitId(owner, "PCS"));
    }

    @Test
    void anNcrSaysWhatItsRejectedQuantityIsCountedIn() throws Exception {
        Lot lot = releasedLot("10");
        String batch = startBatch(product());
        consumeLot(batch, lot.id(), "5").andExpect(status().isOk());
        complete(batch, "5").andExpect(status().isOk());
        String inspection = inspectBatch(batch, "FQC", "25", "REJECT");

        // The only NCR so far in this new organisation.
        String raised = JsonPath.read(body(as(owner, get("/api/v1/ncrs"))), "$.data.items[0].id");
        as(owner, get("/api/v1/ncrs/" + raised))
                .andExpect(jsonPath("$.data.inspectionId").value(inspection))
                .andExpect(jsonPath("$.data.rejectedUomId").value(unitId(owner, "PCS")));

        String material = material(owner);
        String byHand = idOf(body(as(owner, postJson("/api/v1/ncrs", Map.of("title", "Bent bar",
                        "description", "Found in stores", "severity", "MINOR", "materialId", material,
                        "rejectedQuantity", "12")))
                .andExpect(status().isCreated())));
        as(owner, get("/api/v1/ncrs/" + byHand)).andExpect(jsonPath("$.data.rejectedUomId").value(unitId(owner, "KG")));
        as(owner, postJson("/api/v1/ncrs", Map.of("title", "Bent bar", "description", "Found in stores",
                        "severity", "MINOR", "materialId", material, "rejectedQuantity", "12",
                        "rejectedUomId", unitId(owner, "GM"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rejectedUomId").value(unitId(owner, "GM")));
        as(owner, postJson("/api/v1/ncrs", Map.of("title", "Bent bar", "description", "Found in stores",
                        "severity", "MINOR")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rejectedUomId").isEmpty());
    }
}
