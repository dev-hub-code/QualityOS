package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
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
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.ResultActions;

class ManufacturingIT extends TraceabilityFixture {

    private static final String API = "/api/v1/manufacturing";

    private ResultActions send(Session session, String path, Object body) throws Exception {
        return as(session, postJson(API + path, body));
    }

    private ResultActions change(Session session, String path, Object body) throws Exception {
        return as(session, put(API + path).contentType("application/json").content(json.writeValueAsString(body)));
    }

    private String product(String unit) throws Exception {
        return idOf(body(as(owner, postJson("/api/v1/products", Map.of(
                        "code", code(), "name", "Gearbox", "productType", "FINISHED", "uomId", unitId(owner, unit))))
                .andExpect(status().isCreated())));
    }

    private String revisionId(String body) {
        return JsonPath.read(body, "$.data.revision.id");
    }

    private String bom(String product) throws Exception {
        return revisionId(body(send(owner, "/boms", Map.of("productId", product)).andExpect(status().isCreated())));
    }

    private ResultActions line(String path, String id, String key, String component, String quantity, String unit)
            throws Exception {
        return line(path, id, key, component, quantity, unit, Map.of());
    }

    private ResultActions line(String path, String id, String key, String component, String quantity, String unit,
            Map<String, Object> extra) throws Exception {
        Map<String, Object> request = new HashMap<>(Map.of(key, component, "quantity", quantity,
                "uomId", unitId(owner, unit)));
        request.putAll(extra);
        return send(owner, path + "/" + id + "/lines", request);
    }

    /** The owner submits; the manager, someone else, approves. */
    private ResultActions approve(String path, String id, Map<String, Object> request) throws Exception {
        send(owner, path + "/" + id + "/submit", Map.of()).andExpect(status().isOk());
        return send(manager, path + "/" + id + "/approve", request);
    }

    private String approvedBom(String product, String key, String component, String quantity, String unit)
            throws Exception {
        String bom = bom(product);
        line("/boms", bom, key, component, quantity, unit).andExpect(status().isOk());
        approve("/boms", bom, Map.of()).andExpect(status().isOk());
        return bom;
    }

    private String workCenter(String facility) throws Exception {
        return idOf(body(send(owner, "/work-centers", Map.of("code", "WC-" + code(), "name", "CNC cell",
                        "facilityId", facility))
                .andExpect(status().isCreated())));
    }

    private static String inDays(int days) {
        return LocalDate.now().plusDays(days).toString();
    }

    @Test
    void anOrganisationChoosesHowItManufactures() throws Exception {
        as(owner, get(API + "/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.manufacturingModel").value("DISCRETE"))
                .andExpect(jsonPath("$.data.separateApprover").value(true));
        String product = product("KG");
        as(owner, get(API + "/products/" + product + "/settings"))
                .andExpect(jsonPath("$.data.manufacturingModel").value("DISCRETE"))
                .andExpect(jsonPath("$.data.chosen").value(false));

        // A discrete organisation takes BOMs only.
        send(owner, "/recipes", Map.of("productId", product))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("a BOM, not a recipe")));
        change(owner, "/products/" + product + "/settings", Map.of("manufacturingModel", "PROCESS"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("Make it hybrid first")));

        // Hybrid: each product must say how it is made before it takes either.
        change(owner, "/settings", Map.of("manufacturingModel", "HYBRID", "separateApprover", true))
                .andExpect(status().isOk());
        send(owner, "/boms", Map.of("productId", product))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("Choose whether")));
        change(owner, "/products/" + product + "/settings", Map.of("manufacturingModel", "PROCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chosen").value(true));
        send(owner, "/recipes", Map.of("productId", product)).andExpect(status().isCreated());

        // The organisation cannot drop a model its products use.
        change(owner, "/settings", Map.of("manufacturingModel", "DISCRETE", "separateApprover", true))
                .andExpect(status().isUnprocessableContent());
        assertThat(JsonPath.<List<String>>read(body(as(owner, get("/api/v1/audit-logs?size=100"))),
                "$.data.items[*].action")).contains("MANUFACTURING_SETTINGS_CHANGED", "PRODUCT_MANUFACTURING_CHANGED");
    }

    @Test
    void workCentresSitInAFacilityAndOnItsLines() throws Exception {
        String facility = facility(owner);
        String other = facility(owner);
        String line = idOf(body(as(owner, postJson("/api/v1/facilities/" + other + "/production-lines",
                        Map.of("code", "L1", "name", "Line 1")))
                .andExpect(status().isCreated())));
        send(owner, "/work-centers", Map.of("code", "WC-" + code(), "name", "Press", "facilityId", facility,
                        "productionLineId", line))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("This production line is not part of the facility."));

        String centre = idOf(body(send(owner, "/work-centers", Map.of("code", "WC-" + code(), "name", "Press",
                        "facilityId", other, "productionLineId", line))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.productionLineCode").value("L1"))));
        String code = JsonPath.read(body(as(owner, get(API + "/work-centers/" + centre))), "$.data.workCenter.code");
        send(owner, "/work-centers", Map.of("code", code, "name", "Copy", "facilityId", other))
                .andExpect(status().isConflict());
        change(owner, "/work-centers/" + centre, Map.of("code", code, "name", "Press", "facilityId", facility))
                .andExpect(status().isUnprocessableContent());

        // A member limited to another facility does not see it.
        Session limited = addMember(owner, "PRODUCTION_MANAGER");
        limit(limited, facility);
        as(limited, get(API + "/work-centers/" + centre)).andExpect(status().isNotFound());
        as(limited, get(API + "/work-centers")).andExpect(jsonPath("$.data.items[?(@.id == '" + centre + "')]").isEmpty());
        send(limited, "/work-centers", Map.of("code", "WC-" + code(), "name", "Lathe", "facilityId", other))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMachineMovesOnlyThroughAllowedStatusesAndKeepsItsHistory() throws Exception {
        String centre = workCenter(facility(owner));
        String machine = idOf(body(send(owner, "/machines", Map.of("code", "M-" + code(), "name", "Haas VF-2",
                        "workCenterId", centre))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"))));
        String path = "/machines/" + machine + "/status";

        send(owner, path, Map.of("status", "RUNNING")).andExpect(status().isOk());
        send(owner, path, Map.of("status", "BREAKDOWN"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("Say why the machine is breakdown."));
        send(owner, path, Map.of("status", "BREAKDOWN", "reason", "Spindle seized")).andExpect(status().isOk());
        send(owner, path, Map.of("status", "RUNNING"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("A machine that is breakdown cannot become running."));
        send(owner, path, Map.of("status", "MAINTENANCE", "reason", "Spindle replaced"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextStatuses[0]").value("AVAILABLE"));

        String detail = body(as(owner, get(API + "/machines/" + machine)).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(detail, "$.data.history[*].toStatus"))
                .containsExactly("MAINTENANCE", "BREAKDOWN", "RUNNING", "AVAILABLE");
        assertThat(JsonPath.<String>read(detail, "$.data.history[1].reason")).isEqualTo("Spindle seized");

        // History is append-only, whoever asks.
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                        "UPDATE machine_status_events SET reason = 'edited' WHERE machine_id = ?::uuid", machine)))
                .isInstanceOf(DataAccessException.class);

        // A work centre keeps running machines.
        as(owner, delete(API + "/work-centers/" + centre)).andExpect(status().isUnprocessableContent());
        send(owner, path, Map.of("status", "INACTIVE", "reason", "Moved out")).andExpect(status().isOk());
        as(owner, delete(API + "/work-centers/" + centre)).andExpect(status().isNoContent());
        send(owner, "/machines", Map.of("code", "M-" + code(), "name", "New", "workCenterId", centre))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void aBomIsDraftedSubmittedAndApprovedBySomeoneElse() throws Exception {
        String product = product("PCS");
        String material = material(owner, false, "KG");
        String bom = bom(product);

        line("/boms", bom, "materialId", material, "3", "PCS")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("A quantity in PCS cannot be converted to KG."));
        line("/boms", bom, "materialId", material, "250", "GM", Map.of("scrapPercent", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].lineNumber").value(1))
                .andExpect(jsonPath("$.data.lines[0].componentType").value("MATERIAL"));
        send(owner, "/boms", Map.of("productId", product))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value(containsString("already has a BOM revision in progress")));

        send(owner, "/boms/" + bom + "/submit", Map.of()).andExpect(status().isOk());
        line("/boms", bom, "materialId", material, "1", "KG")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("cannot be changed")));
        send(owner, "/boms/" + bom + "/approve", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("You submitted this revision, so someone else must approve it."));
        send(manager, "/boms/" + bom + "/send-back", Map.of("note", "Scrap is too low")).andExpect(status().isOk());
        send(owner, "/boms/" + bom + "/submit", Map.of()).andExpect(status().isOk());
        String approved = body(send(manager, "/boms/" + bom + "/approve", Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.revision.inEffect").value(true))
                .andExpect(jsonPath("$.data.revision.approvedBy").value(manager.userId().toString())));

        // Approved content is frozen: in the snapshot, and by the database whatever the caller.
        String snapshot = asDatabaseSuperuser().queryForObject(
                "SELECT snapshot::text FROM boms WHERE id = ?::uuid", String.class, bom);
        assertThat(snapshot).contains("\"quantity\": \"250").contains("\"productCode\"");
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                        "UPDATE bom_lines SET quantity = 9 WHERE bom_id = ?::uuid", bom)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                        "DELETE FROM boms WHERE id = ?::uuid", bom)))
                .isInstanceOf(DataAccessException.class);
        as(owner, delete(API + "/boms/" + bom)).andExpect(status().isUnprocessableContent());
        assertThat(JsonPath.<String>read(approved, "$.data.revision.effectiveFrom")).isNotNull();
    }

    @Test
    void aNewerRevisionEndsTheOneBeforeIt() throws Exception {
        String product = product("PCS");
        String material = material(owner, false, "KG");
        String first = approvedBom(product, "materialId", material, "1", "KG");

        String second = revisionId(body(send(owner, "/boms", Map.of("productId", product, "copyFromId", first,
                        "changeNote", "Heavier bar"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.revision.revision").value(2))
                .andExpect(jsonPath("$.data.lines[0].componentId").value(material))));
        String lineId = JsonPath.read(body(as(owner, get(API + "/boms/" + second))), "$.data.lines[0].id");
        change(owner, "/boms/" + second + "/lines/" + lineId,
                Map.of("materialId", material, "quantity", "1.2", "uomId", unitId(owner, "KG")))
                .andExpect(status().isOk());
        send(owner, "/boms/" + second + "/submit", Map.of()).andExpect(status().isOk());
        send(manager, "/boms/" + second + "/approve", Map.of("effectiveFrom", inDays(-3)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("A revision cannot take effect before today."));
        send(manager, "/boms/" + second + "/approve", Map.of("effectiveFrom", inDays(10))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision.inEffect").value(false));

        as(owner, get(API + "/boms/" + first))
                .andExpect(jsonPath("$.data.revision.effectiveTo").value(inDays(9)))
                .andExpect(jsonPath("$.data.revision.inEffect").value(true));

        // A third revision cannot start before the second has.
        String third = revisionId(body(send(owner, "/boms", Map.of("productId", product, "copyFromId", second))
                .andExpect(status().isCreated())));
        send(owner, "/boms/" + third + "/submit", Map.of()).andExpect(status().isOk());
        send(manager, "/boms/" + third + "/approve", Map.of("effectiveFrom", inDays(5)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("Revision 2 takes effect on")));

        // The explosion uses whichever revision is in effect on the day asked for.
        String now = body(as(owner, get(API + "/boms/" + third + "/explosion?quantity=10")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Object>>read(now, "$.data.rows")).hasSize(1);
        send(manager, "/boms/" + first + "/retire", Map.of("reason", "Replaced")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision.status").value("OBSOLETE"));
        send(manager, "/boms/" + first + "/retire", Map.of("reason", "Again")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void aProductCannotBeMadeFromItselfAtAnyDepth() throws Exception {
        String assembly = product("PCS");
        String subAssembly = product("PCS");
        String part = product("PCS");
        String material = material(owner, false, "KG");
        approvedBom(assembly, "componentProductId", subAssembly, "2", "PCS");
        approvedBom(subAssembly, "componentProductId", part, "1", "PCS");

        String own = bom(part);
        line("/boms", own, "componentProductId", part, "1", "PCS")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("cannot be a component of itself")));
        line("/boms", own, "componentProductId", assembly, "1", "PCS")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value(containsString("is already made from")));
        line("/boms", own, "materialId", material, "1", "KG").andExpect(status().isOk());
    }

    @Test
    void anExplosionWalksEveryLevelInEachComponentsUnit() throws Exception {
        String assembly = product("PCS");
        String shaft = product("PCS");
        String steel = material(owner, false, "KG");
        String grease = material(owner, false, "KG");
        // One shaft takes 500 g of steel.
        approvedBom(shaft, "materialId", steel, "500", "GM");
        // Two assemblies take 3 shafts, 100 g of grease with 10% scrap, and 1 kg of steel.
        String bom = bom(assembly);
        change(owner, "/boms/" + bom, Map.of("baseQuantity", "2")).andExpect(status().isOk());
        line("/boms", bom, "componentProductId", shaft, "3", "PCS").andExpect(status().isOk());
        line("/boms", bom, "materialId", grease, "100", "GM", Map.of("scrapPercent", "10")).andExpect(status().isOk());
        line("/boms", bom, "materialId", steel, "1", "KG").andExpect(status().isOk());

        String exploded = body(as(owner, get(API + "/boms/" + bom + "/explosion?quantity=10"))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<List<Integer>>read(exploded, "$.data.rows[*].level")).containsExactly(1, 2, 1, 1);
        assertThat(JsonPath.<Boolean>read(exploded, "$.data.rows[0].exploded")).isTrue();
        assertThat(new BigDecimal(JsonPath.<String>read(exploded, "$.data.rows[0].quantity")))
                .isEqualByComparingTo("15");
        // 15 shafts at 0.5 kg; grease 0.5 kg plus 10%.
        assertThat(new BigDecimal(JsonPath.<String>read(exploded, "$.data.rows[1].quantity")))
                .isEqualByComparingTo("7.5");
        assertThat(new BigDecimal(JsonPath.<String>read(exploded, "$.data.rows[2].quantity")))
                .isEqualByComparingTo("0.55");
        // Steel totals both uses: 7.5 kg through the shafts and 5 kg directly.
        assertThat(new BigDecimal(JsonPath.<List<String>>read(exploded,
                "$.data.totals[?(@.componentId == '" + steel + "')].quantity").getFirst()))
                .isEqualByComparingTo("12.5");
        as(owner, get(API + "/boms/" + bom + "/explosion?quantity=0")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void aRecipeScalesItsProportionalLinesAndKeepsItsParameters() throws Exception {
        change(owner, "/settings", Map.of("manufacturingModel", "PROCESS", "separateApprover", false))
                .andExpect(status().isOk());
        String product = product("KG");
        String resin = material(owner, false, "KG");
        String catalyst = material(owner, false, "KG");
        String recipe = revisionId(body(send(owner, "/recipes", Map.of("productId", product, "baseQuantity", "100"))
                .andExpect(status().isCreated())));
        line("/recipes", recipe, "materialId", resin, "95", "KG").andExpect(status().isOk());
        line("/recipes", recipe, "materialId", catalyst, "500", "GM", Map.of("scales", false))
                .andExpect(status().isOk());

        Map<String, Object> outside = Map.of("code", "TEMP", "name", "Reactor temperature", "unit", "°C",
                "target", "200", "minimum", "175", "maximum", "185");
        send(owner, "/recipes/" + recipe + "/parameters", outside)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("The target is outside the limits."));
        send(owner, "/recipes/" + recipe + "/parameters", Map.of("code", "TEMP", "name", "Reactor temperature",
                        "unit", "°C", "target", "180", "minimum", "175", "maximum", "185"))
                .andExpect(status().isOk());
        send(owner, "/recipes/" + recipe + "/parameters", Map.of("code", "TEMP", "name", "Again", "target", "1"))
                .andExpect(status().isConflict());

        String scaled = body(as(owner, get(API + "/recipes/" + recipe + "/scaled?batchSize=250"))
                .andExpect(status().isOk()));
        assertThat(new BigDecimal(JsonPath.<String>read(scaled, "$.data.factor"))).isEqualByComparingTo("2.5");
        assertThat(new BigDecimal(JsonPath.<String>read(scaled, "$.data.lines[0].quantity")))
                .isEqualByComparingTo("237.5");
        assertThat(new BigDecimal(JsonPath.<String>read(scaled, "$.data.lines[1].quantity")))
                .isEqualByComparingTo("500");
        assertThat(JsonPath.<String>read(scaled, "$.data.parameters[0].code")).isEqualTo("TEMP");

        // Without a separate approver, whoever submits may approve.
        send(owner, "/recipes/" + recipe + "/submit", Map.of()).andExpect(status().isOk());
        send(owner, "/recipes/" + recipe + "/approve", Map.of()).andExpect(status().isOk());
        send(owner, "/boms/" + recipe + "/submit", Map.of()).andExpect(status().isNotFound());
    }

    @Test
    void aRoutingSequencesOperationsAtWorkCentres() throws Exception {
        String product = product("PCS");
        String centre = workCenter(facility(owner));
        String routing = revisionId(body(send(owner, "/routings", Map.of("productId", product))
                .andExpect(status().isCreated())));
        send(owner, "/routings/" + routing + "/submit", Map.of())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("Add at least one operation before submitting."));

        Map<String, Object> turning = Map.of("sequence", 10, "name", "Turning", "workCenterId", centre,
                "setupMinutes", "30", "runMinutes", "4.5");
        String detail = body(send(owner, "/routings/" + routing + "/operations", turning).andExpect(status().isOk()));
        send(owner, "/routings/" + routing + "/operations", turning).andExpect(status().isConflict());
        send(owner, "/routings/" + routing + "/operations", Map.of("sequence", 20, "name", "Grinding",
                        "workCenterId", centre))
                .andExpect(status().isOk());
        String operation = JsonPath.read(detail, "$.data.operations[0].id");
        send(owner, "/routings/" + routing + "/operations/" + operation + "/parameters",
                Map.of("code", "RPM", "name", "Spindle speed", "minimum", "800", "maximum", "1200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operations[0].parameters[0].code").value("RPM"))
                .andExpect(jsonPath("$.data.operations[1].name").value("Grinding"));

        approve("/routings", routing, Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision.status").value("APPROVED"));
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), jdbc -> jdbc.update(
                        "UPDATE process_parameters SET name = 'x' WHERE code = 'RPM'")))
                .isInstanceOf(DataAccessException.class);

        // Copies carry their operations and parameters, and only a never-submitted draft can be discarded.
        String copy = revisionId(body(send(owner, "/routings", Map.of("productId", product, "copyFromId", routing))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.operations[0].parameters[0].code").value("RPM"))));
        as(owner, delete(API + "/routings/" + copy)).andExpect(status().isNoContent());
        as(owner, get(API + "/routings/" + copy)).andExpect(status().isNotFound());
    }

    @Test
    void aBomCanStartFromTheProductsMaterialList() throws Exception {
        String product = product("PCS");
        String material = material(owner, false, "KG");
        as(owner, postJson("/api/v1/products/" + product + "/materials", Map.of("materialId", material,
                        "quantityPerUnit", "0.75", "uomId", unitId(owner, "KG"))))
                .andExpect(status().isCreated());
        String created = body(send(owner, "/boms", Map.of("productId", product, "fromProductMaterials", true,
                        "baseQuantity", "4"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lines[0].componentId").value(material)));
        assertThat(new BigDecimal(JsonPath.<String>read(created, "$.data.lines[0].quantity")))
                .isEqualByComparingTo("3");
    }

    @Test
    void rolesDecideWhoMayViewDraftAndApprove() throws Exception {
        String product = product("PCS");
        Session viewer = addMember(owner, "VIEWER");
        Session production = addMember(owner, "PRODUCTION_MANAGER");
        Session inspector = addMember(owner, "QUALITY_INSPECTOR");

        as(viewer, get(API + "/boms")).andExpect(status().isOk());
        send(viewer, "/boms", Map.of("productId", product)).andExpect(status().isForbidden());
        send(viewer, "/work-centers", Map.of("code", "X", "name", "X", "facilityId", facility(owner)))
                .andExpect(status().isForbidden());

        String bom = revisionId(body(send(production, "/boms", Map.of("productId", product))
                .andExpect(status().isCreated())));
        line("/boms", bom, "materialId", material(owner, false, "KG"), "1", "KG").andExpect(status().isOk());
        send(production, "/boms/" + bom + "/submit", Map.of()).andExpect(status().isOk());
        send(inspector, "/boms/" + bom + "/approve", Map.of()).andExpect(status().isForbidden());
        send(manager, "/boms/" + bom + "/approve", Map.of()).andExpect(status().isOk());
    }

    @Test
    void manufacturingNeedsAPlanThatIncludesIt() throws Exception {
        String product = product("PCS");
        as(owner, get("/api/v1/auth/me")).andExpect(jsonPath("$.data.permissions", org.hamcrest.Matchers.hasItem("BOM_MANAGE")));
        asDatabaseSuperuser().update(
                "UPDATE subscriptions SET plan_id = (SELECT id FROM subscription_plans WHERE code = 'STARTER') "
                        + "WHERE tenant_id = ?", owner.tenantId());
        send(owner, "/boms", Map.of("productId", product)).andExpect(status().isForbidden());
        // What was made before stays readable.
        as(owner, get(API + "/boms")).andExpect(status().isOk());
    }

    @Test
    void anotherOrganisationsDefinitionsAreInvisible() throws Exception {
        String bom = bom(product("PCS"));
        String centre = workCenter(facility(owner));
        Session stranger = register();
        as(stranger, get(API + "/boms/" + bom)).andExpect(status().isNotFound());
        as(stranger, get(API + "/work-centers/" + centre)).andExpect(status().isNotFound());
        as(stranger, get(API + "/boms")).andExpect(jsonPath("$.data.items").isEmpty());
        send(stranger, "/boms/" + bom + "/lines", Map.of("materialId", material(stranger, false, "KG"),
                        "quantity", "1", "uomId", unitId(stranger, "KG")))
                .andExpect(status().isNotFound());
    }
}
