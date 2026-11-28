package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** A unit stays what its records were made in, an organisation adds its own, and typed amounts convert exactly. */
class UnitIntegrityIT extends TraceabilityFixture {

    private ResultActions changeUnit(String path, String id, String unit) throws Exception {
        Map<String, Object> current = JsonPath.read(body(as(owner, get(path + "/" + id))),
                path.endsWith("products") ? "$.data.product" : "$.data.material");
        Map<String, Object> request = new HashMap<>(Map.of("code", current.get("code"), "name", current.get("name"),
                "uomId", unitId(owner, unit), "version", current.get("version")));
        if (path.endsWith("products")) {
            request.put("productType", "FINISHED");
        } else {
            request.put("inspectionRequired", current.get("inspectionRequired"));
        }
        return as(owner, put(path + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)));
    }

    private ResultActions addUnit(Map<String, Object> request) throws Exception {
        return as(owner, postJson("/api/v1/units", request));
    }

    private ResultActions consume(String batch, String key, String source, String quantity, String unit)
            throws Exception {
        return as(owner, postJson("/api/v1/production-batches/" + batch + "/consume",
                Map.of(key, source, "quantity", quantity, "uomId", unitId(owner, unit))));
    }

    @Test
    void aMaterialsUnitIsFixedOnceStockIsRecordedInIt() throws Exception {
        String unused = material(owner, false);
        changeUnit("/api/v1/materials", unused, "GM").andExpect(status().isOk());

        String received = material(owner, false);
        lot(received, "10");
        changeUnit("/api/v1/materials", received, "LTR")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("is on purchase orders in KG, so its unit "
                        + "cannot change")));
        // Everything else about it can still be edited.
        changeUnit("/api/v1/materials", received, "KG").andExpect(status().isOk());
    }

    @Test
    void aProductsUnitIsFixedOnceABatchIsMadeOfIt() throws Exception {
        String unused = product();
        changeUnit("/api/v1/products", unused, "KG").andExpect(status().isOk());

        String made = product();
        String batch = startBatch(made);
        changeUnit("/api/v1/products", made, "KG")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("is on production batches in PCS")));
        as(owner, get("/api/v1/production-batches/" + batch))
                .andExpect(jsonPath("$.data.batch.uomId").value(unitId(owner, "PCS")));
    }

    @Test
    void anOrganisationDefinesAUnitFromOneItEquals() throws Exception {
        String created = body(addUnit(Map.of("code", "mg", "name", "Milligram",
                        "equalsQuantity", "0.001", "equalsUnitId", unitId(owner, "GM")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("MG"))
                .andExpect(jsonPath("$.data.dimension").value("Mass")));
        assertThat(new BigDecimal(JsonPath.<String>read(created, "$.data.toBase"))).isEqualByComparingTo("0.000001");

        Lot lot = releasedLot("10");
        String batch = startBatch(product());
        String detail = body(consume(batch, "receiptItemId", lot.id(), "2500", "MG").andExpect(status().isOk()));
        assertThat(new BigDecimal(JsonPath.<String>read(detail, "$.data.consumptions[0].quantity")))
                .isEqualByComparingTo("0.0025");
    }

    @Test
    void aNewUnitsKindMustBeOneThatExistsWhenItConverts() throws Exception {
        // The kind is matched whatever its case, and keeps the spelling already in use.
        addUnit(Map.of("code", "KL", "name", "Kilolitre", "dimension", "volume", "toBase", "1000"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.dimension").value("Volume"));
        addUnit(Map.of("code", "QTL", "name", "Quintal", "dimension", "Mas", "toBase", "100"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("No unit measures Mas yet")));
        addUnit(Map.of("code", "QTL", "name", "Quintal", "dimension", "Mass"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("Mass units convert to one another")));
        addUnit(Map.of("code", "HV", "name", "Vickers", "dimension", "hardness", "toBase", "2"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("leave the factor out")));
        addUnit(Map.of("code", "HV2", "name", "Vickers", "equalsQuantity", "2", "equalsUnitId", unitId(owner, "HRC")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("HRC does not convert")));
        addUnit(Map.of("code", "TEN PCS", "name", "Ten", "equalsQuantity", "10", "equalsUnitId",
                        unitId(owner, "PCS")))
                .andExpect(status().isUnprocessableContent());

        // A reading that never converts needs no kind at all.
        addUnit(Map.of("code", "CP", "name", "Centipoise"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.dimension").value("Other"))
                .andExpect(jsonPath("$.data.toBase").isEmpty());
    }

    @Test
    void aPackOfPiecesMayBeSplitIntoWholePieces() throws Exception {
        addUnit(Map.of("code", "DZN", "name", "Dozen", "equalsQuantity", "12", "equalsUnitId", unitId(owner, "PCS")))
                .andExpect(status().isCreated());
        String inDozens = idOf(body(as(owner, postJson("/api/v1/products", Map.of("code", code(), "name", "Vials",
                        "productType", "FINISHED", "uomId", unitId(owner, "DZN"))))
                .andExpect(status().isCreated())));

        as(owner, postJson("/api/v1/production-batches", Map.of("productId", inDozens, "facilityId", facility(owner),
                        "plannedQuantity", "2.5")))
                .andExpect(status().isCreated());
        as(owner, postJson("/api/v1/production-batches", Map.of("productId", inDozens, "facilityId", facility(owner),
                        "plannedQuantity", "2.55")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("a quantity that makes whole pieces")));
    }

    @Test
    void aTypedAmountThatDoesNotConvertExactlyIsRefused() throws Exception {
        Lot lot = releasedLot("10");
        String batch = startBatch(product());
        consume(batch, "receiptItemId", lot.id(), "0.0005", "GM")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("kept to 6 decimal places")));
        consume(batch, "receiptItemId", lot.id(), "1.5", "GM").andExpect(status().isOk());

        String pieces = releasedBatch(releasedLot("10").id(), "10", "30");
        String assembly = startBatch(product());
        addUnit(Map.of("code", "DOZN", "name", "Dozen", "equalsQuantity", "12", "equalsUnitId", unitId(owner, "PCS")))
                .andExpect(status().isCreated());
        String drawn = body(consume(assembly, "parentBatchId", pieces, "1.5", "DOZN").andExpect(status().isOk()));
        assertThat(new BigDecimal(JsonPath.<List<String>>read(drawn, "$.data.consumptions[*].quantity").getFirst()))
                .isEqualByComparingTo("18");
    }
}
