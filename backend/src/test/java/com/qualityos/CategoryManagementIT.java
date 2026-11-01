package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CategoryManagementIT extends AbstractIntegrationTest {

    private static final String SUPPLIERS = "/api/v1/suppliers/categories";
    private static final String PRODUCTS = "/api/v1/product-categories";

    private Session owner;

    @BeforeEach
    void setUp() throws Exception {
        owner = register();
    }

    private String add(Session session, String path, String name) throws Exception {
        return JsonPath.read(as(session, postJson(path, Map.of("name", name))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.data.id");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rename(String url, String name) {
        return put(url).contentType("application/json").content(json.writeValueAsString(Map.of("name", name)));
    }

    private long audited(String action) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND action = ?", Long.class, owner.tenantId(), action);
    }

    @ParameterizedTest
    @ValueSource(strings = {SUPPLIERS, PRODUCTS})
    void aCategoryIsRenamedAndDeleted(String path) throws Exception {
        String id = add(owner, path, "Forgings");

        as(owner, rename(path + "/" + id, " Castings ")).andExpect(jsonPath("$.data.name").value("Castings"));
        as(owner, get(path)).andExpect(jsonPath("$.data[0].name").value("Castings"));

        as(owner, delete(path + "/" + id)).andExpect(status().isNoContent());
        as(owner, get(path)).andExpect(jsonPath("$.data").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {SUPPLIERS, PRODUCTS})
    void aNameIsNotTakenTwiceButKeepingItsOwnIsFine(String path) throws Exception {
        add(owner, path, "Forgings");
        String other = add(owner, path, "Castings");

        as(owner, rename(path + "/" + other, "Forgings")).andExpect(status().isConflict());
        as(owner, rename(path + "/" + other, "Castings")).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {SUPPLIERS, PRODUCTS})
    void anotherOrganisationCannotTouchACategory(String path) throws Exception {
        String id = add(owner, path, "Forgings");
        Session other = register();

        as(other, rename(path + "/" + id, "Mine")).andExpect(status().isNotFound());
        as(other, delete(path + "/" + id)).andExpect(status().isNotFound());
        as(owner, get(path)).andExpect(jsonPath("$.data[0].name").value("Forgings"));
    }

    @Test
    void aSupplierCategoryInUseIsKept() throws Exception {
        String id = add(owner, SUPPLIERS, "Forgings");
        as(owner, postJson("/api/v1/suppliers", Map.of("code", "S-1", "name", "Bharat", "categoryId", id)))
                .andExpect(status().isCreated());

        as(owner, delete(SUPPLIERS + "/" + id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("This category is in use, so it cannot be deleted."));
        as(owner, get(SUPPLIERS)).andExpect(jsonPath("$.data[0].name").value("Forgings"));
        assertThat(audited("SUPPLIER_CATEGORY_DELETED")).isZero();
    }

    @Test
    void aProductCategoryInUseByAMaterialIsKept() throws Exception {
        String id = add(owner, PRODUCTS, "Steel");
        String unit = asDatabaseSuperuser().queryForObject(
                "SELECT id::text FROM units_of_measure WHERE tenant_id IS NULL LIMIT 1", String.class);
        as(owner, postJson("/api/v1/materials", Map.of("code", "M-1", "name", "Bar", "uomId", unit, "categoryId", id,
                "inspectionRequired", true))).andExpect(status().isCreated());

        as(owner, delete(PRODUCTS + "/" + id)).andExpect(status().isConflict());
    }

    @Test
    void everyChangeIsAudited() throws Exception {
        String id = add(owner, SUPPLIERS, "Forgings");
        as(owner, rename(SUPPLIERS + "/" + id, "Castings")).andExpect(status().isOk());
        as(owner, delete(SUPPLIERS + "/" + id)).andExpect(status().isNoContent());

        assertThat(audited("SUPPLIER_CATEGORY_CREATED")).isEqualTo(1);
        assertThat(audited("SUPPLIER_CATEGORY_RENAMED")).isEqualTo(1);
        assertThat(audited("SUPPLIER_CATEGORY_DELETED")).isEqualTo(1);
    }

    @Test
    void anUnknownCategoryIsNotFound() throws Exception {
        as(owner, delete(SUPPLIERS + "/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
