package com.qualityos;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

class SerialSearchIT extends TraceabilityFixture {

    private static final String SERIALS = "/api/v1/traceability/serials";

    private String batch;
    private String prefix;
    private String serial;

    @BeforeEach
    void unitsMade() throws Exception {
        prefix = "Sn" + code();
        serial = prefix + "-001";
        batch = batchWithSerials(List.of(serial, prefix + "-002", prefix + "-003"));
    }

    private String batchWithSerials(List<String> serials) throws Exception {
        String made = startBatch(product());
        consumeLot(made, releasedLot("5").id(), "3").andExpect(status().isOk());
        complete(made, String.valueOf(serials.size())).andExpect(status().isOk());
        as(owner, postJson("/api/v1/production-batches/" + made + "/serials", Map.of("serialNumbers", serials)))
                .andExpect(status().isOk());
        inspectBatch(made, "FQC", "20.0", "ACCEPT");
        return made;
    }

    private ResultActions search(Session session, String query) throws Exception {
        return as(session, get(SERIALS + query));
    }

    private String facilityOf(String productionBatch) throws Exception {
        return JsonPath.read(body(as(owner, get("/api/v1/production-batches/" + productionBatch))), "$.data.batch.facilityId");
    }

    private void limit(Session member, String facility) throws Exception {
        String users = body(as(owner, get("/api/v1/users?size=100")));
        List<String> ids = JsonPath.read(users, "$.data.items[?(@.userId == '" + member.userId() + "')].membershipId");
        as(owner, put("/api/v1/users/" + ids.getFirst() + "/facilities").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("facilityIds", List.of(facility)))))
                .andExpect(status().isOk());
    }

    @Test
    void findsAUnitByExactOrPartialNumberInAnyCase() throws Exception {
        search(owner, "?q=" + serial)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].serialNumber").value(serial))
                .andExpect(jsonPath("$.data.items[0].productName").value("Drive Shaft"))
                .andExpect(jsonPath("$.data.items[0].qualityStatus").value("RELEASED"))
                .andExpect(jsonPath("$.data.items[0].inspectionStatus").value("PASS"))
                .andExpect(jsonPath("$.data.items[0].dispatchStatus").value("NOT_DISPATCHED"))
                .andExpect(jsonPath("$.data.totalItems").value(1));
        search(owner, "?q=" + prefix.toLowerCase()).andExpect(jsonPath("$.data.totalItems").value(3));
    }

    @Test
    void anExactMatchComesFirstHoweverTheRestAreSorted() throws Exception {
        search(owner, "?q=" + prefix + "-002&sort=serialNumber,desc")
                .andExpect(jsonPath("$.data.items[0].serialNumber").value(prefix + "-002"));
        search(owner, "?q=" + prefix + "&sort=serialNumber,desc")
                .andExpect(jsonPath("$.data.items[0].serialNumber").value(prefix + "-003"));
        search(owner, "?q=" + prefix + "&sort=password").andExpect(status().isBadRequest());
    }

    @Test
    void pagesThroughResults() throws Exception {
        search(owner, "?q=" + prefix + "&size=2&page=1")
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].serialNumber").value(prefix + "-003"))
                .andExpect(jsonPath("$.data.totalPages").value(2));
        search(owner, "?q=" + prefix + "&size=101").andExpect(status().isBadRequest());
    }

    @Test
    void searchesEachFieldOnItsOwn() throws Exception {
        String number = numberOf(batch);
        search(owner, "?field=BATCH&q=" + number).andExpect(jsonPath("$.data.totalItems").value(3));
        search(owner, "?field=SERIAL&q=" + number).andExpect(jsonPath("$.data.totalItems").value(0));
        String dispatch = body(dispatch(customer(), batch, "1", List.of(serial)).andExpect(status().isCreated()));
        String dispatchNumber = JsonPath.read(dispatch, "$.data.dispatchNumber");
        search(owner, "?field=DISPATCH&q=" + dispatchNumber)
                .andExpect(jsonPath("$.data.items[0].serialNumber").value(serial))
                .andExpect(jsonPath("$.data.items[0].customerName").value("Tata Motors"))
                .andExpect(jsonPath("$.data.items[0].dispatchStatus").value("AWAITING_SHIPMENT"));
        search(owner, "?field=NOPE&q=" + serial).andExpect(status().isUnprocessableContent());
    }

    @Test
    void wildcardsInTheSearchAreLiteral() throws Exception {
        search(owner, "?q=%25%25").andExpect(jsonPath("$.data.totalItems").value(0));
        search(owner, "?field=SERIAL&q=" + prefix + "_001").andExpect(jsonPath("$.data.totalItems").value(0));
    }

    @Test
    void filtersByFacilityAndDate() throws Exception {
        String facility = facilityOf(batch);
        search(owner, "?facilityId=" + facility).andExpect(jsonPath("$.data.totalItems").value(3));
        search(owner, "?facilityId=" + UUID.randomUUID()).andExpect(jsonPath("$.data.totalItems").value(0));
        search(owner, "?q=" + prefix + "&madeFrom=" + LocalDate.now().plusDays(2))
                .andExpect(jsonPath("$.data.totalItems").value(0));
        search(owner, "?q=" + prefix + "&madeTo=" + LocalDate.now().plusDays(2))
                .andExpect(jsonPath("$.data.totalItems").value(3));
        search(owner, "?q=" + prefix + "&dispatchedFrom=" + TODAY).andExpect(jsonPath("$.data.totalItems").value(0));
    }

    @Test
    void refusesToListEverything() throws Exception {
        search(owner, "").andExpect(status().isUnprocessableContent());
        search(owner, "?q=a").andExpect(status().isUnprocessableContent());
    }

    @Test
    void anotherTenantSeesNothing() throws Exception {
        Session stranger = register();
        search(stranger, "?q=" + serial).andExpect(jsonPath("$.data.totalItems").value(0));
        as(stranger, get(SERIALS + "/" + serial)).andExpect(status().isNotFound());
        as(stranger, get("/api/v1/traceability/search?q=" + serial)).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void theInspectionColumnNeedsPermissionToSeeInspections() throws Exception {
        String role = idOf(body(as(owner, postJson("/api/v1/roles", Map.of(
                        "name", "Trace only " + code(), "permissions", List.of("TRACEABILITY_VIEW"))))
                .andExpect(status().isCreated())));
        Session traceOnly = addMember(owner, "VIEWER");
        String users = body(as(owner, get("/api/v1/users?size=100")));
        List<String> ids = JsonPath.read(users, "$.data.items[?(@.userId == '" + traceOnly.userId() + "')].membershipId");
        as(owner, put("/api/v1/users/" + ids.getFirst() + "/roles").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("roleIds", List.of(role)))))
                .andExpect(status().isOk());
        search(traceOnly, "?q=" + serial)
                .andExpect(jsonPath("$.data.items[0].serialNumber").value(serial))
                .andExpect(jsonPath("$.data.items[0].inspectionStatus").doesNotExist());
    }

    @Test
    void traceabilitySearchLeadsToTheUnit() throws Exception {
        as(owner, get("/api/v1/traceability/search?q=" + serial))
                .andExpect(jsonPath("$.data[0].kind").value("SERIAL"))
                .andExpect(jsonPath("$.data[0].serialNumber").value(serial))
                .andExpect(jsonPath("$.data[0].batchNumber").value(numberOf(batch)));
    }

    @Test
    void theDetailShowsProductBatchAndInspections() throws Exception {
        as(owner, get(SERIALS + "/" + serial))
                .andExpect(jsonPath("$.data.productName").value("Drive Shaft"))
                .andExpect(jsonPath("$.data.batchNumber").value(numberOf(batch)))
                .andExpect(jsonPath("$.data.batchStatus").value("RELEASED"))
                .andExpect(jsonPath("$.data.inspections.length()").value(1));
    }

    @Test
    void aMemberLimitedToOneFacilityCannotReachUnitsInAnother() throws Exception {
        String otherSerial = "Sn" + code();
        String other = batchWithSerials(List.of(otherSerial));
        Session limited = addMember(owner, "QUALITY_MANAGER");
        limit(limited, facilityOf(batch));

        search(limited, "?q=" + serial).andExpect(jsonPath("$.data.totalItems").value(1));
        as(limited, get(SERIALS + "/" + serial)).andExpect(status().isOk());

        search(limited, "?q=" + otherSerial).andExpect(jsonPath("$.data.totalItems").value(0));
        search(limited, "?facilityId=" + facilityOf(other)).andExpect(jsonPath("$.data.totalItems").value(0));
        as(limited, get(SERIALS + "/" + otherSerial)).andExpect(status().isNotFound());
        as(limited, get("/api/v1/traceability/search?q=" + otherSerial)).andExpect(jsonPath("$.data.length()").value(0));
        as(limited, get("/api/v1/traceability/batches/" + numberOf(other))).andExpect(status().isNotFound());
        as(limited, get("/api/v1/production-batches/" + other)).andExpect(status().isNotFound());
        as(limited, get("/api/v1/production-batches?size=100"))
                .andExpect(jsonPath("$.data.items[?(@.id == '" + other + "')]").isEmpty());
    }

    @Test
    void aTraceLeavingAMembersFacilityHidesTheUnitsAndCustomersBeyondIt() throws Exception {
        Lot lot = releasedLot("10");
        String first = startBatch(product());
        consumeLot(first, lot.id(), "1").andExpect(status().isOk());
        String second = startBatch(product());
        consumeLot(second, lot.id(), "1").andExpect(status().isOk());
        complete(second, "2").andExpect(status().isOk());
        String hidden = "Sn" + code();
        as(owner, postJson("/api/v1/production-batches/" + second + "/serials", Map.of("serialNumbers", List.of(hidden))))
                .andExpect(status().isOk());
        Session limited = addMember(owner, "QUALITY_MANAGER");
        limit(limited, facilityOf(first));

        as(owner, get("/api/v1/traceability/batches/" + lot.batch()))
                .andExpect(jsonPath("$.data.serialNumbers[0]").value(hidden));
        as(limited, get("/api/v1/traceability/batches/" + lot.batch()))
                .andExpect(jsonPath("$.data.serialNumbers.length()").value(0));
    }

    @Test
    void onlyFacilitiesInThisOrganisationCanBeAssigned() throws Exception {
        Session stranger = register();
        String theirs = facility(stranger);
        Session member = addMember(owner, "VIEWER");
        String users = body(as(owner, get("/api/v1/users?size=100")));
        List<String> ids = JsonPath.read(users, "$.data.items[?(@.userId == '" + member.userId() + "')].membershipId");
        as(owner, put("/api/v1/users/" + ids.getFirst() + "/facilities").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("facilityIds", List.of(theirs)))))
                .andExpect(status().isUnprocessableContent());
        as(manager, put("/api/v1/users/" + ids.getFirst() + "/facilities").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("facilityIds", List.of()))))
                .andExpect(status().isForbidden());
    }
}
