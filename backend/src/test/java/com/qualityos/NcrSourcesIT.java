package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Supplier issues and process incidents, raised by hand as NCRs of their own source. */
class NcrSourcesIT extends TraceabilityFixture {

    private String plant;
    private String line;
    private String centre;
    private String machine;

    @BeforeEach
    void places() throws Exception {
        plant = facility(owner);
        line = idOf(body(as(owner, postJson("/api/v1/facilities/" + plant + "/production-lines",
                        Map.of("code", "L-" + code(), "name", "Assembly")))
                .andExpect(status().isCreated())));
        centre = idOf(body(as(owner, postJson("/api/v1/manufacturing/work-centers", Map.of("code", "WC-" + code(),
                        "name", "Pressing", "facilityId", plant, "labourRate", "600", "overheadRate", "120")))
                .andExpect(status().isCreated())));
        machine = idOf(body(as(owner, postJson("/api/v1/manufacturing/machines", Map.of("code", "M-" + code(),
                        "name", "Press 2", "workCenterId", centre)))
                .andExpect(status().isCreated())));
    }

    private ResultActions raise(Session session, Map<String, Object> extra) throws Exception {
        Map<String, Object> request = new HashMap<>(Map.of(
                "title", "Oil leak", "description", "Found at the start of the shift", "severity", "MAJOR"));
        request.putAll(extra);
        return as(session, postJson("/api/v1/ncrs", request));
    }

    @Test
    void aProcessIncidentNamesWhereItHappenedAndTakesThatPlant() throws Exception {
        String body = body(raise(owner, Map.of("source", "PROCESS", "workCenterId", centre, "machineId", machine))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.source").value("PROCESS"))
                .andExpect(jsonPath("$.data.machineId").value(machine))
                .andExpect(jsonPath("$.data.place").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("Work centre WC-"),
                        org.hamcrest.Matchers.containsString(" · Machine M-"),
                        org.hamcrest.Matchers.endsWith("Press 2")))));
        String id = JsonPath.read(body, "$.data.id");
        assertThat(inTransactionAs(owner.tenantId(), null, () -> jdbc.queryForObject(
                "SELECT facility_id::text FROM non_conformances WHERE id = ?::uuid", String.class, id)))
                .isEqualTo(plant);
        // Raised by a person, so it can be cancelled as a mistake like any other raised by hand.
        as(owner, postJson("/api/v1/ncrs/" + id + "/cancel", Map.of("note", "Wrong press"))).andExpect(status().isOk());

        raise(owner, Map.of("source", "PROCESS", "productionLineId", line)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.place").value(org.hamcrest.Matchers.startsWith("Line L-")));
    }

    @Test
    void aProcessIncidentMustSayWhereAndTheRestMustAgree() throws Exception {
        raise(owner, Map.of("source", "PROCESS")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message")
                        .value("Say where the incident happened: a production line, work centre or machine."));

        String otherPlant = facility(owner);
        String otherCentre = idOf(body(as(owner, postJson("/api/v1/manufacturing/work-centers", Map.of(
                        "code", "WC-" + code(), "name", "Welding", "facilityId", otherPlant,
                        "labourRate", "600", "overheadRate", "120")))
                .andExpect(status().isCreated())));
        raise(owner, Map.of("source", "PROCESS", "workCenterId", otherCentre, "machineId", machine))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("This machine is not at that work centre."));
        raise(owner, Map.of("source", "PROCESS", "productionLineId", line, "workCenterId", otherCentre))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message")
                        .value("The line, work centre and machine must be at the same plant."));
        raise(owner, Map.of("source", "PROCESS", "machineId", machine, "facilityId", otherPlant))
                .andExpect(status().isUnprocessableContent());

        // Places belong to process incidents only, and other sources are raised by their own records.
        raise(owner, Map.of("machineId", machine)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message")
                        .value("Only a process incident says which line, work centre or machine."));
        raise(owner, Map.of("source", "INSPECTION")).andExpect(status().isUnprocessableContent());

        // Another organisation's machine is nowhere to be found.
        Session stranger = register();
        raise(stranger, Map.of("source", "PROCESS", "machineId", machine))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("This machine does not exist."));
    }

    @Test
    void aSupplierIssueNeedsItsSupplierAndCountsAgainstIt() throws Exception {
        raise(owner, Map.of("source", "SUPPLIER")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.message").value("A supplier issue needs its supplier."));
        String supplier = approvedSupplier(owner);
        raise(owner, Map.of("source", "SUPPLIER", "supplierId", supplier)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.source").value("SUPPLIER"))
                .andExpect(jsonPath("$.data.supplierId").value(supplier));
        // An older client that sends no source raises one by hand, as before.
        raise(owner, Map.of()).andExpect(status().isCreated()).andExpect(jsonPath("$.data.source").value("MANUAL"));
    }

    @Test
    void whoeverRaisesNcrsCanListThePlacesInTheirOwnPlants() throws Exception {
        Session inspector = addMember(owner, "QUALITY_INSPECTOR");
        as(inspector, get("/api/v1/ncrs/places")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[*].id").value(org.hamcrest.Matchers.hasItem(line)))
                .andExpect(jsonPath("$.data.machines[?(@.id == '" + machine + "')].workCenterId")
                        .value(org.hamcrest.Matchers.contains(centre)));
        raise(inspector, Map.of("source", "PROCESS", "machineId", machine)).andExpect(status().isCreated());

        String otherPlant = facility(owner);
        limit(inspector, otherPlant);
        as(inspector, get("/api/v1/ncrs/places")).andExpect(jsonPath("$.data.machines.length()").value(0));
        raise(inspector, Map.of("source", "PROCESS", "machineId", machine)).andExpect(status().isNotFound());

        Session viewer = addMember(owner, "VIEWER");
        as(viewer, get("/api/v1/ncrs/places")).andExpect(status().isForbidden());
    }
}
