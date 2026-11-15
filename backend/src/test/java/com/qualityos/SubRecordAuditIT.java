package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Changes to the parts of a record, not only the record, leave a trace in the audit log. */
class SubRecordAuditIT extends TraceabilityFixture {

    private List<Map<String, Object>> entries(String action) {
        return asDatabaseSuperuser().queryForList(
                "SELECT entity_id::text AS entity, previous_state::text AS before, new_state::text AS after"
                        + " FROM audit_logs WHERE tenant_id = ? AND action = ?", owner.tenantId(), action);
    }

    private String add(String path, Map<String, Object> body) throws Exception {
        return idOf(body(as(owner, postJson("/api/v1" + path, body)).andExpect(status().is2xxSuccessful())));
    }

    @Test
    void addingAndRemovingPartsIsAudited() throws Exception {
        String facility = facility(owner);
        String department = add("/facilities/" + facility + "/departments", Map.of("code", "QA", "name", "Quality"));
        String line = add("/facilities/" + facility + "/production-lines", Map.of("code", "L1", "name", "Line 1"));
        String shift = add("/facilities/" + facility + "/shifts", Map.of("name", "Day", "startsAt", "08:00",
                "endsAt", "16:00"));
        as(owner, delete("/api/v1/facilities/" + facility + "/departments/" + department)).andExpect(status().isNoContent());
        as(owner, delete("/api/v1/facilities/" + facility + "/production-lines/" + line)).andExpect(status().isNoContent());
        as(owner, delete("/api/v1/facilities/" + facility + "/shifts/" + shift)).andExpect(status().isNoContent());

        String supplier = draftSupplier(owner);
        String contact = add("/suppliers/" + supplier + "/contacts", Map.of("name", "Ravi Kulkarni", "primary", true));
        as(owner, delete("/api/v1/suppliers/" + supplier + "/contacts/" + contact)).andExpect(status().isNoContent());

        String product = product();
        String mapping = add("/products/" + product + "/materials", Map.of("materialId", material(owner, false),
                "quantityPerUnit", "1.5", "uomId", unitId(owner, "KG")));
        as(owner, delete("/api/v1/products/" + product + "/materials/" + mapping)).andExpect(status().isNoContent());

        add("/units", Map.of("code", "BOX", "name", "Box of ten", "dimension", "COUNT", "toBase", "10"));

        assertThat(entries("DEPARTMENT_ADDED")).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("entity", facility))
                .satisfies(row -> assertThat((String) row.get("after")).contains("QA"));
        assertThat(entries("DEPARTMENT_ARCHIVED")).singleElement()
                .satisfies(row -> assertThat((String) row.get("before")).contains("QA"));
        assertThat(entries("PRODUCTION_LINE_ADDED")).hasSize(1);
        assertThat(entries("PRODUCTION_LINE_ARCHIVED")).hasSize(1);
        assertThat(entries("SHIFT_ADDED")).singleElement()
                .satisfies(row -> assertThat((String) row.get("after")).contains("Day", "08:00"));
        assertThat(entries("SHIFT_ARCHIVED")).hasSize(1);
        assertThat(entries("SUPPLIER_CONTACT_ADDED")).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("entity", supplier));
        assertThat(entries("SUPPLIER_CONTACT_REMOVED")).singleElement()
                .satisfies(row -> assertThat((String) row.get("before")).contains("Ravi Kulkarni"));
        assertThat(entries("PRODUCT_MATERIAL_ADDED")).singleElement()
                .satisfies(row -> assertThat((String) row.get("after")).contains("1.5"));
        assertThat(entries("PRODUCT_MATERIAL_REMOVED")).hasSize(1);
        assertThat(entries("UNIT_ADDED")).singleElement()
                .satisfies(row -> assertThat((String) row.get("after")).contains("BOX", "COUNT"));
    }
}
