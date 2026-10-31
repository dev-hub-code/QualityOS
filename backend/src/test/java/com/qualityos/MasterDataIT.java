package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MasterDataIT extends MasterDataFixture {

    @Test
    void facilityHoldsDepartmentsLinesAndShifts() throws Exception {
        Session owner = register();
        String id = facility(owner);

        as(owner, postJson("/api/v1/facilities/" + id + "/departments", Map.of("code", "QA", "name", "Quality")))
                .andExpect(status().isCreated());
        as(owner, postJson("/api/v1/facilities/" + id + "/production-lines", Map.of("code", "L1", "name", "CNC Line")))
                .andExpect(status().isCreated());
        as(owner, postJson("/api/v1/facilities/" + id + "/shifts",
                        Map.of("name", "Night", "startsAt", "22:00", "endsAt", "06:00")))
                .andExpect(status().isCreated());

        as(owner, get("/api/v1/facilities/" + id))
                .andExpect(jsonPath("$.data.departments[0].code").value("QA"))
                .andExpect(jsonPath("$.data.productionLines[0].code").value("L1"))
                .andExpect(jsonPath("$.data.shifts[0].name").value("Night"));
    }

    @Test
    void codesAreUniquePerTenantButReusableAcrossTenants() throws Exception {
        Session first = register();
        Session second = register();
        Map<String, String> request = Map.of("code", "SUP-001", "name", "Acme");

        as(first, postJson("/api/v1/suppliers", request)).andExpect(status().isCreated());
        as(first, postJson("/api/v1/suppliers", request)).andExpect(status().isConflict());
        as(second, postJson("/api/v1/suppliers", request)).andExpect(status().isCreated());
    }

    @Test
    void supplierMovesThroughApproval() throws Exception {
        Session owner = register();
        String id = draftSupplier(owner);
        String path = "/api/v1/suppliers/" + id;

        as(owner, postJson(path + "/approve", Map.of())).andExpect(status().isUnprocessableContent());
        as(owner, postJson(path + "/submit", Map.of()))
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));
        as(owner, postJson(path + "/reject", Map.of("note", "Missing certificate")))
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.statusNote").value("Missing certificate"));
        as(owner, postJson(path + "/submit", Map.of())).andExpect(status().isOk());
        as(owner, postJson(path + "/approve", Map.of()))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.approvedAt").isNotEmpty());
        as(owner, postJson(path + "/block", Map.of("note", "Repeated rejections")))
                .andExpect(jsonPath("$.data.status").value("BLOCKED"));
        as(owner, postJson(path + "/reinstate", Map.of()))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        as(owner, get("/api/v1/audit-logs"))
                .andExpect(jsonPath("$.data.items[*].action", hasItem("SUPPLIER_STATUS_CHANGED")));
    }

    @Test
    void statusCannotBeSetThroughAnUpdate() throws Exception {
        Session owner = register();
        String id = draftSupplier(owner);

        as(owner, put("/api/v1/suppliers/" + id).contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "code", "X", "name", "Renamed", "status", "APPROVED", "version", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void rejectingOrBlockingNeedsAReason() throws Exception {
        Session owner = register();
        String id = draftSupplier(owner);
        as(owner, postJson("/api/v1/suppliers/" + id + "/submit", Map.of())).andExpect(status().isOk());

        as(owner, postJson("/api/v1/suppliers/" + id + "/reject", Map.of())).andExpect(status().isBadRequest());
    }

    @Test
    void inspectorCannotApproveSuppliers() throws Exception {
        Session owner = register();
        Session inspector = addMember(owner, "QUALITY_INSPECTOR");
        String id = draftSupplier(owner);
        as(owner, postJson("/api/v1/suppliers/" + id + "/submit", Map.of())).andExpect(status().isOk());

        as(inspector, postJson("/api/v1/suppliers/" + id + "/approve", Map.of())).andExpect(status().isForbidden());
    }

    @Test
    void searchFiltersAndSortingWork() throws Exception {
        Session owner = register();
        as(owner, postJson("/api/v1/suppliers", Map.of("code", "A1", "name", "Alpha Forgings")));
        as(owner, postJson("/api/v1/suppliers", Map.of("code", "B1", "name", "Beta Castings")));

        as(owner, get("/api/v1/suppliers").param("q", "forg"))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].code").value("A1"));
        as(owner, get("/api/v1/suppliers").param("sort", "name,desc"))
                .andExpect(jsonPath("$.data.items[0].code").value("B1"));
        as(owner, get("/api/v1/suppliers").param("status", "APPROVED"))
                .andExpect(jsonPath("$.data.totalItems").value(0));
    }

    @Test
    void searchTreatsWildcardsLiterally() throws Exception {
        Session owner = register();
        as(owner, postJson("/api/v1/suppliers", Map.of("code", "A1", "name", "Alpha")));

        as(owner, get("/api/v1/suppliers").param("q", "%")).andExpect(jsonPath("$.data.totalItems").value(0));
    }

    @Test
    void unsafeSortAndOversizedPagesAreRefused() throws Exception {
        Session owner = register();

        as(owner, get("/api/v1/suppliers").param("sort", "tenantId")).andExpect(status().isBadRequest());
        as(owner, get("/api/v1/suppliers").param("size", "1000")).andExpect(status().isBadRequest());
    }

    @Test
    void archivedRecordsLeaveListsButRemainReadable() throws Exception {
        Session owner = register();
        String id = draftSupplier(owner);

        as(owner, delete("/api/v1/suppliers/" + id)).andExpect(status().isNoContent());

        as(owner, get("/api/v1/suppliers")).andExpect(jsonPath("$.data.totalItems").value(0));
        as(owner, get("/api/v1/suppliers/" + id)).andExpect(status().isOk());
    }

    @Test
    void sharedUnitsAreSeededAndTenantsCanAddTheirOwn() throws Exception {
        Session owner = register();
        Session other = register();

        as(owner, get("/api/v1/units")).andExpect(jsonPath("$.data[*].code", hasItem("HRC")));
        as(owner, postJson("/api/v1/units", Map.of("code", "kN", "name", "Kilonewton", "dimension", "Force")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("KN"));
        as(owner, postJson("/api/v1/units", Map.of("code", "HRC", "name", "Duplicate", "dimension", "Hardness")))
                .andExpect(status().isConflict());

        String otherUnits = body(as(other, get("/api/v1/units")));
        assertThat(otherUnits).doesNotContain("Kilonewton");
    }

    @Test
    void measurementsKeepTheirPrecision() throws Exception {
        Session owner = register();
        String material = material(owner);

        as(owner, postJson("/api/v1/materials/" + material + "/specifications", Map.of(
                        "name", "Diameter", "minValue", "19.900001", "maxValue", "20.100001",
                        "uomId", unitId(owner, "MM"))))
                .andExpect(status().isCreated());

        String detail = body(as(owner, get("/api/v1/materials/" + material)));
        assertThat(JsonPath.<String>read(detail, "$.data.specifications[0].minValue")).isEqualTo("19.900001");
    }

    @Test
    void specificationRangeMustBeValid() throws Exception {
        Session owner = register();

        as(owner, postJson("/api/v1/materials/" + material(owner) + "/specifications",
                        Map.of("name", "Diameter", "minValue", "20", "maxValue", "19")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void productCarriesRevisionsSpecificationsAndMaterials() throws Exception {
        Session owner = register();
        String material = material(owner);
        String product = idOf(body(as(owner, postJson("/api/v1/products", Map.of(
                        "code", code(), "name", "Drive Shaft", "productType", "FINISHED",
                        "uomId", unitId(owner, "PCS"))))
                .andExpect(status().isCreated())));

        as(owner, postJson("/api/v1/products/" + product + "/revisions",
                        Map.of("revision", "B", "changeNote", "Longer spline", "effectiveFrom", "2026-11-01")))
                .andExpect(status().isCreated());
        as(owner, postJson("/api/v1/products/" + product + "/revisions",
                        Map.of("revision", "B", "effectiveFrom", "2026-11-01")))
                .andExpect(status().isConflict());
        as(owner, postJson("/api/v1/products/" + product + "/materials", Map.of(
                        "materialId", material, "quantityPerUnit", "1.25", "uomId", unitId(owner, "KG"))))
                .andExpect(status().isCreated());

        as(owner, get("/api/v1/products/" + product))
                .andExpect(jsonPath("$.data.product.currentRevision").value("B"))
                .andExpect(jsonPath("$.data.revisions.length()").value(2))
                .andExpect(jsonPath("$.data.materials[0].materialName").value("EN8 Bar"))
                .andExpect(jsonPath("$.data.materials[0].quantityPerUnit").value("1.250000"));
    }

    @Test
    void anotherTenantsMaterialCannotBeMappedToAProduct() throws Exception {
        Session owner = register();
        Session other = register();
        String product = idOf(body(as(owner, postJson("/api/v1/products", Map.of(
                "code", code(), "name", "Shaft", "productType", "FINISHED", "uomId", unitId(owner, "PCS"))))));

        as(owner, postJson("/api/v1/products/" + product + "/materials", Map.of(
                        "materialId", material(other), "quantityPerUnit", "1", "uomId", unitId(owner, "KG"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void documentNumbersRunPerTenant() throws Exception {
        Session first = register();
        Session second = register();
        String supplier = approvedSupplier(first);
        String material = material(first);

        String one = JsonPath.read(draftOrder(first, supplier, material, "10"), "$.data.poNumber");
        String two = JsonPath.read(draftOrder(first, supplier, material, "10"), "$.data.poNumber");
        String other = JsonPath.read(draftOrder(second, approvedSupplier(second), material(second), "10"),
                "$.data.poNumber");

        assertThat(one).endsWith("-00001");
        assertThat(two).endsWith("-00002");
        assertThat(other).endsWith("-00001");
        assertThat(List.of(one, two)).doesNotHaveDuplicates();
    }
}
