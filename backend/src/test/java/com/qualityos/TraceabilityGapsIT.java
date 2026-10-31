package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.traceability.service.DispatchService;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.ResultActions;

class TraceabilityGapsIT extends TraceabilityFixture {

    private static final String TOMORROW = LocalDate.now().plusDays(1).toString();

    @Autowired
    private DispatchService dispatches;

    private String batch;
    private String dispatch;
    private String line;

    @BeforeEach
    void shipped() throws Exception {
        batch = releasedBatch(releasedLot("100").id(), "100", "100");
        String response = body(dispatch(customer(), batch, "100", List.of()).andExpect(status().isCreated()));
        dispatch = idOf(response);
        line = JsonPath.read(response, "$.data.lines[0].id");
        ship(dispatch).andExpect(status().isOk());
    }

    private ResultActions announce(String reference, String quantity, String date) throws Exception {
        return as(owner, postJson("/api/v1/dispatches/" + dispatch + "/reversals", Map.of(
                "returnReference", reference, "reversalDate", date, "reason", "Wrong size", "expected", true,
                "items", List.of(Map.of("dispatchItemId", line, "quantity", quantity)))));
    }

    private ResultActions receive(String reversal, String date) throws Exception {
        return as(owner, postJson("/api/v1/dispatch-reversals/" + reversal + "/receive", Map.of("receivedOn", date)));
    }

    private String summary(String field) throws Exception {
        return JsonPath.read(body(as(owner, get("/api/v1/dispatches/" + dispatch + "/reversal-summary"))), "$.data." + field);
    }

    @Test
    void anExpectedReturnCannotBeInspectedUntilItArrives() throws Exception {
        String response = body(announce("RMA-1", "40", TOMORROW).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("EXPECTED")));
        String reversal = idOf(response);
        String item = JsonPath.read(response, "$.data.items[0].id");
        inspect("returnItemId", item, "FQC").andExpect(status().isUnprocessableContent());
        assertThat(summary("status")).isEqualTo("NOT_REVERSED");
        assertThat(new java.math.BigDecimal(summary("expectedQuantity"))).isEqualByComparingTo("40");

        receive(reversal, TOMORROW).andExpect(status().isUnprocessableContent());
        receive(reversal, TODAY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RETURN_RECEIVED"))
                .andExpect(jsonPath("$.data.reversalDate").value(TODAY));
        assertThat(summary("status")).isEqualTo("PARTIALLY_REVERSED");
        assertThat(new java.math.BigDecimal(summary("expectedQuantity"))).isEqualByComparingTo("0");
        inspect("returnItemId", item, "FQC").andExpect(status().isCreated());
        receive(reversal, TODAY).andExpect(status().isUnprocessableContent());
    }

    @Test
    void expectedQuantityIsHeldAndFreedWhenCancelled() throws Exception {
        String reversal = idOf(body(announce("RMA-1", "100", TOMORROW).andExpect(status().isCreated())));
        announce("RMA-2", "1", TODAY).andExpect(status().isUnprocessableContent());

        as(owner, postJson("/api/v1/dispatch-reversals/" + reversal + "/cancel", Map.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        announce("RMA-2", "100", TODAY).andExpect(status().isCreated());
        as(owner, postJson("/api/v1/dispatch-reversals/" + reversal + "/cancel", Map.of()))
                .andExpect(status().isUnprocessableContent());
        assertThatThrownBy(() -> asAppRole(owner.tenantId(), sql -> sql.update(
                "UPDATE dispatch_reversals SET status = 'EXPECTED' WHERE status = 'CANCELLED'")))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void aViewerAndAnotherTenantCannotReceiveOrCancel() throws Exception {
        String reversal = idOf(body(announce("RMA-1", "1", TOMORROW).andExpect(status().isCreated())));
        String path = "/api/v1/dispatch-reversals/" + reversal;
        as(addMember(owner, "VIEWER"), postJson(path + "/receive", Map.of("receivedOn", TODAY)))
                .andExpect(status().isForbidden());
        as(register(), postJson(path + "/cancel", Map.of())).andExpect(status().isNotFound());
    }

    @Test
    void theRecallReportShowsWhatEachCustomerStillHolds() throws Exception {
        as(owner, postJson("/api/v1/dispatches/" + dispatch + "/reversals", Map.of(
                        "returnReference", "RMA-1", "reversalDate", TODAY, "reason", "Damaged",
                        "items", List.of(Map.of("dispatchItemId", line, "quantity", "30")))))
                .andExpect(status().isCreated());
        announce("RMA-2", "5", TOMORROW).andExpect(status().isCreated());

        as(owner, get("/api/v1/traceability/batches/" + numberOf(batch)))
                .andExpect(jsonPath("$.data.recall[0].customerName").value("Tata Motors"))
                .andExpect(jsonPath("$.data.recall[0].quantity").value("100.000000"))
                .andExpect(jsonPath("$.data.recall[0].returnedQuantity").value("30.000000"))
                .andExpect(jsonPath("$.data.recall[0].outstandingQuantity").value("70.000000"))
                .andExpect(jsonPath("$.data.unconfirmedCustomers.length()").value(0));
    }

    @Test
    void anUnconfirmedCustomerIsFlaggedOnTheTraceAndACancelledOneDropsOff() throws Exception {
        String other = releasedBatch(releasedLot("5").id(), "5", "5");
        String pending = idOf(body(dispatch(customer(), other, "1", List.of()).andExpect(status().isCreated())));
        as(owner, get("/api/v1/traceability/batches/" + numberOf(other)))
                .andExpect(jsonPath("$.data.affectedCustomers[0]").value("Tata Motors"))
                .andExpect(jsonPath("$.data.unconfirmedCustomers[0]").value("Tata Motors"))
                .andExpect(jsonPath("$.data.recall[0].shippedOn").doesNotExist());

        as(owner, postJson("/api/v1/dispatches/" + pending + "/reverse", Map.of("reason", "Mistake")))
                .andExpect(status().isOk());
        as(owner, get("/api/v1/traceability/batches/" + numberOf(other)))
                .andExpect(jsonPath("$.data.recall.length()").value(0))
                .andExpect(jsonPath("$.data.unconfirmedCustomers.length()").value(0));
    }

    @Test
    void aSerialNumberRemembersEveryCustomerItReached() throws Exception {
        String serialBatch = startBatch(product());
        consumeLot(serialBatch, releasedLot("3").id(), "2").andExpect(status().isOk());
        complete(serialBatch, "2").andExpect(status().isOk());
        String serial = "S-" + code();
        String other = "S-" + code();
        as(owner, postJson("/api/v1/production-batches/" + serialBatch + "/serials",
                        Map.of("serialNumbers", List.of(serial, other))))
                .andExpect(status().isOk());
        inspectBatch(serialBatch, "FQC", "20.0", "ACCEPT");
        String response = body(dispatch(customer(), serialBatch, "1", List.of(serial)).andExpect(status().isCreated()));
        String first = idOf(response);
        ship(first).andExpect(status().isOk());
        String item = JsonPath.read(body(as(owner, postJson("/api/v1/dispatches/" + first + "/reversals", Map.of(
                        "returnReference", "RMA-S", "reversalDate", TODAY, "reason", "Faulty",
                        "items", List.of(Map.of("dispatchItemId", JsonPath.read(response, "$.data.lines[0].id"),
                                "quantity", "1", "serialNumbers", List.of(serial))))))
                .andExpect(status().isCreated())), "$.data.items[0].id");
        String inspection = idOf(body(inspect("returnItemId", item, "FQC").andExpect(status().isCreated())));
        String path = "/api/v1/inspections/" + inspection;
        as(owner, postJson(path + "/execute", Map.of("results", List.of(
                Map.of("parameterCode", "DIA", "sampleNumber", 1, "valueNumeric", "20.0"))))).andExpect(status().isOk());
        as(owner, postJson(path + "/submit", Map.of())).andExpect(status().isOk());
        as(manager, postJson(path + "/approve", Map.of())).andExpect(status().isOk());
        as(manager, postJson(path + "/disposition", Map.of("disposition", "ACCEPT"))).andExpect(status().isOk());
        String second = as(owner, postJson("/api/v1/customers", Map.of("code", code(), "name", "Bajaj Auto")))
                .andReturn().getResponse().getContentAsString();
        dispatch(idOf(second), serialBatch, "1", List.of(serial)).andExpect(status().isCreated());

        as(owner, get("/api/v1/traceability/serials/" + serial))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveries.length()").value(2))
                .andExpect(jsonPath("$.data.deliveries[0].customerName").value("Tata Motors"))
                .andExpect(jsonPath("$.data.deliveries[0].returned").value(true))
                .andExpect(jsonPath("$.data.deliveries[1].customerName").value("Bajaj Auto"))
                .andExpect(jsonPath("$.data.deliveries[1].returned").value(false));
        as(owner, get("/api/v1/traceability/serials/" + other))
                .andExpect(jsonPath("$.data.deliveries.length()").value(0));
        as(owner, get("/api/v1/traceability/serials/UNKNOWN")).andExpect(status().isNotFound());
        as(register(), get("/api/v1/traceability/serials/" + serial)).andExpect(status().isNotFound());
    }

    @Test
    void aForgottenShipmentIsRaisedOncePerDayToThoseWhoCanConfirmIt() throws Exception {
        String other = releasedBatch(releasedLot("5").id(), "5", "5");
        String old = LocalDate.now().minusDays(5).toString();
        as(owner, postJson("/api/v1/dispatches", Map.of("customerId", customer(), "dispatchedOn", old, "items",
                        List.of(Map.of("productionBatchId", other, "quantity", "1")))))
                .andExpect(status().isCreated());
        Session stranger = register();

        asAppRole(null, sql -> {
            assertThat(sql.queryForList("SELECT tenants_with_unconfirmed_dispatches()", java.util.UUID.class))
                    .contains(owner.tenantId());
            assertThat(sql.queryForObject("SELECT count(*) FROM dispatch_records", Long.class)).isZero();
        });
        assertThat(inTransactionAs(owner.tenantId(), null, () -> dispatches.remindUnconfirmed())).isEqualTo(1);
        assertThat(inTransactionAs(owner.tenantId(), null, () -> dispatches.remindUnconfirmed())).isZero();
        assertThat(inTransactionAs(stranger.tenantId(), null, () -> dispatches.remindUnconfirmed())).isZero();
        as(owner, get("/api/v1/notifications"))
                .andExpect(jsonPath("$.data.items[0].type").value("DISPATCH_SHIPMENT_UNCONFIRMED"));
    }
}
