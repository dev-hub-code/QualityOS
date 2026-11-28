package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * An item's own conversions: glycerin kept in KG is bought in 200 kg drums and measured in litres at 1.26 kg each,
 * so orders, receipts, material lists and consumption take those units and keep the stock in KG.
 */
class ItemUnitConversionIT extends TraceabilityFixture {

    private String drum;

    private String drumUnit() throws Exception {
        if (drum == null) {
            drum = idOf(body(as(owner, postJson("/api/v1/units", Map.of("code", "DRUM", "name", "Drum")))
                    .andExpect(status().isCreated())));
        }
        return drum;
    }

    private ResultActions addConversion(String item, String unit, String factor) throws Exception {
        return as(owner, postJson("/api/v1/items/" + item + "/unit-conversions", Map.of("uomId", unit, "factor", factor)));
    }

    /** Glycerin in KG, at 200 KG a drum and 1.26 KG a litre. */
    private String glycerin() throws Exception {
        String material = material(owner, false, "KG");
        addConversion(material, drumUnit(), "200").andExpect(status().isCreated());
        addConversion(material, unitId(owner, "LTR"), "1.26").andExpect(status().isCreated());
        return material;
    }

    private String orderInDrums(String material, String drums, String price) throws Exception {
        Map<String, Object> line = Map.of("materialId", material, "quantity", drums, "unitPrice", price,
                "uomId", drumUnit());
        String created = body(as(owner, postJson("/api/v1/purchase-orders", Map.of("supplierId",
                        approvedSupplier(owner), "facilityId", facility(owner), "orderDate", LocalDate.now().toString(),
                        "items", List.of(line))))
                .andExpect(status().isCreated()));
        as(owner, postJson("/api/v1/purchase-orders/" + idOf(created) + "/issue", Map.of())).andExpect(status().isOk());
        return created;
    }

    private ResultActions receiveIn(String order, String quantity, String unit) throws Exception {
        return as(owner, postJson("/api/v1/material-receipts", Map.of("purchaseOrderId", idOf(order),
                "receivedOn", LocalDate.now().toString(), "items", List.of(Map.of(
                        "purchaseOrderItemId", JsonPath.read(order, "$.data.items[0].id"), "supplierBatch", "GL-7",
                        "quantity", quantity, "uomId", unit)))));
    }

    private static BigDecimal decimal(String body, String path) {
        return new BigDecimal(JsonPath.<String>read(body, path));
    }

    @Test
    void anItemSaysWhichUnitsItsQuantitiesMayBeEnteredIn() throws Exception {
        String material = glycerin();
        as(owner, get("/api/v1/items/" + material + "/units"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stockUnitId").value(unitId(owner, "KG")))
                .andExpect(jsonPath("$.data.conversions.length()").value(2))
                .andExpect(jsonPath("$.data.enterableUnitIds", hasItems(unitId(owner, "KG"), unitId(owner, "GM"),
                        drumUnit(), unitId(owner, "LTR"), unitId(owner, "ML"))))
                .andExpect(jsonPath("$.data.enterableUnitIds", not(hasItem(unitId(owner, "PCS")))));
    }

    @Test
    void aConversionIsRefusedWhereItWouldDisagreeWithAnother() throws Exception {
        String material = glycerin();
        addConversion(material, unitId(owner, "GM"), "0.002")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("GM already converts to KG for every item."));
        addConversion(material, unitId(owner, "KG"), "1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("is kept in KG already")));
        addConversion(material, unitId(owner, "ML"), "0.00126")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("already converts through LTR")));
        addConversion(material, drumUnit(), "180").andExpect(status().isConflict());
        // Round 9: QualityOS's readings, such as °C, are never an amount of stock.
        addConversion(material, unitId(owner, "°C"), "5")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("°C is a reading")));
        addConversion(material, unitId(owner, "PCS"), "0")
                .andExpect(status().isBadRequest());
    }

    @Test
    void anOrderInDrumsExpectsKilogramsAndAReceiptMayBeCountedInDrumsOrLitres() throws Exception {
        String material = glycerin();
        String order = orderInDrums(material, "4", "9000");
        assertThat(decimal(order, "$.data.items[0].orderedQuantity")).isEqualByComparingTo("800");
        assertThat(decimal(order, "$.data.items[0].enteredQuantity")).isEqualByComparingTo("4");
        assertThat(JsonPath.<String>read(order, "$.data.items[0].enteredUomId")).isEqualTo(drumUnit());
        assertThat(JsonPath.<String>read(order, "$.data.items[0].uomId")).isEqualTo(unitId(owner, "KG"));

        String drums = body(receiveIn(order, "2", drumUnit()).andExpect(status().isCreated()));
        assertThat(decimal(drums, "$.data.items[0].quantity")).isEqualByComparingTo("400");
        assertThat(decimal(drums, "$.data.items[0].enteredQuantity")).isEqualByComparingTo("2");
        assertThat(JsonPath.<String>read(drums, "$.data.items[0].enteredUomId")).isEqualTo(drumUnit());

        String litres = body(receiveIn(order, "126", unitId(owner, "LTR")).andExpect(status().isCreated()));
        assertThat(decimal(litres, "$.data.items[0].quantity")).isEqualByComparingTo("158.76");

        // 800 - 558.76 = 241.24 KG is left: 2 more drums (400 KG) is too much.
        receiveIn(order, "2", drumUnit()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString(
                        "this receipt comes to 400 KG, but only 241.24 KG remains to be received.")));
        String after = body(as(owner, get("/api/v1/purchase-orders/" + idOf(order))));
        assertThat(decimal(after, "$.data.items[0].receivedQuantity")).isEqualByComparingTo("558.76");
    }

    @Test
    void aUnitWithNoConversionForTheItemIsRefusedWithWhatToAdd() throws Exception {
        String material = material(owner, false, "KG");
        String product = product();
        as(owner, postJson("/api/v1/products/" + product + "/materials", Map.of(
                        "materialId", material, "quantityPerUnit", "0.5", "uomId", unitId(owner, "LTR"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("Add a unit conversion to")));

        addConversion(material, unitId(owner, "LTR"), "1.26").andExpect(status().isCreated());
        as(owner, postJson("/api/v1/products/" + product + "/materials", Map.of(
                        "materialId", material, "quantityPerUnit", "0.5", "uomId", unitId(owner, "LTR"))))
                .andExpect(status().isCreated());
    }

    @Test
    void aLotInKilogramsIsDrawnOnInLitres() throws Exception {
        String material = glycerin();
        Lot lot = lot(material, "100");
        String batch = startBatch(product());
        String detail = body(as(owner, postJson("/api/v1/production-batches/" + batch + "/consume", Map.of(
                        "receiptItemId", lot.id(), "quantity", "10", "uomId", unitId(owner, "LTR"))))
                .andExpect(status().isOk()));
        assertThat(decimal(detail, "$.data.consumptions[0].quantity")).isEqualByComparingTo("12.6");
        // 500 ML goes through the litre's conversion: 0.63 KG.
        String more = body(as(owner, postJson("/api/v1/production-batches/" + batch + "/consume", Map.of(
                        "receiptItemId", lot.id(), "quantity", "500", "uomId", unitId(owner, "ML"))))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(more, "$.data.consumptions[*].quantity"))
                .anySatisfy(quantity -> assertThat(new BigDecimal(quantity)).isEqualByComparingTo("0.63"));
    }

    @Test
    void aTypedAmountMustConvertExactlyThroughTheItemsConversion() throws Exception {
        String material = material(owner, false, "KG");
        addConversion(material, unitId(owner, "LTR"), "1.234567891").andExpect(status().isCreated());
        Lot lot = lot(material, "100");
        String batch = startBatch(product());
        as(owner, postJson("/api/v1/production-batches/" + batch + "/consume", Map.of(
                        "receiptItemId", lot.id(), "quantity", "1", "uomId", unitId(owner, "LTR"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("kept to 6 decimal places")));
    }

    @Test
    void aConversionStaysWhileSomethingIsEnteredInItsUnit() throws Exception {
        String material = glycerin();
        orderInDrums(material, "1", "9000");
        String units = body(as(owner, get("/api/v1/items/" + material + "/units")));
        String drumRule = JsonPath.<List<String>>read(units, "$.data.conversions[?(@.unitCode == 'DRUM')].id").getFirst();
        String litreRule = JsonPath.<List<String>>read(units, "$.data.conversions[?(@.unitCode == 'LTR')].id").getFirst();

        as(owner, delete("/api/v1/items/" + material + "/unit-conversions/" + drumRule))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("is entered in DRUM on purchase orders")));
        as(owner, delete("/api/v1/items/" + material + "/unit-conversions/" + litreRule))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversions.length()").value(1));
    }

    @Test
    void anItemWithConversionsKeepsItsUnit() throws Exception {
        String material = material(owner, false, "KG");
        addConversion(material, drumUnit(), "200").andExpect(status().isCreated());
        Map<String, Object> current = JsonPath.read(body(as(owner, get("/api/v1/materials/" + material))),
                "$.data.material");
        Map<String, Object> request = new HashMap<>(Map.of("code", current.get("code"), "name", current.get("name"),
                "uomId", unitId(owner, "LTR"), "version", current.get("version"), "inspectionRequired", false));
        as(owner, put("/api/v1/materials/" + material).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("has unit conversions to KG")));
    }

    @Test
    void aPackIsSizedPerItemUnlessItHasOneSizeForAll() throws Exception {
        String box = idOf(body(as(owner, postJson("/api/v1/units", Map.of("code", "BOX", "name", "Box of ten",
                        "equalsQuantity", "10", "equalsUnitId", unitId(owner, "PCS"))))
                .andExpect(status().isCreated())));
        String carton = idOf(body(as(owner, postJson("/api/v1/units", Map.of("code", "CTN", "name", "Carton")))
                .andExpect(status().isCreated())));
        String vials = product();
        // A box is 10 pieces for every item, so one item cannot make it 12.
        addConversion(vials, box, "12")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("BOX already converts to PCS for every item."));
        // A carton has no size of its own: each product says how many it holds.
        addConversion(vials, carton, "12").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.enterableUnitIds", hasItems(carton, box, unitId(owner, "PCS"))));
        String other = product();
        addConversion(other, carton, "48").andExpect(status().isCreated());
    }
}
