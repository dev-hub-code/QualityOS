package com.qualityos;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.ResultActions;

class DispatchReturnIT extends TraceabilityFixture {

    private String batch;
    private String dispatch;
    private String line;

    @BeforeEach
    void shipped() throws Exception {
        batch = releasedBatch(releasedLot("1000").id(), "1000", "1000");
        String response = body(dispatch(customer(), batch, "1000", List.of()).andExpect(status().isCreated()));
        dispatch = idOf(response);
        line = JsonPath.read(response, "$.data.lines[0].id");
    }

    private Map<String, Object> returnOf(String reference, String quantity, List<String> serials) {
        return Map.of("returnReference", reference, "reversalDate", TODAY, "reason", "Customer complaint",
                "items", List.of(Map.of("dispatchItemId", line, "quantity", quantity, "serialNumbers", serials)));
    }

    private ResultActions recordReturn(String reference, String quantity) throws Exception {
        return as(owner, postJson("/api/v1/dispatches/" + dispatch + "/reversals", returnOf(reference, quantity, List.of())));
    }

    private String returnItem(ResultActions result) throws Exception {
        return JsonPath.read(body(result.andExpect(status().isCreated())), "$.data.items[0].id");
    }

    /** Inspects returned goods: the owner measures, and the manager decides. */
    private String inspectReturn(String item, String value, String disposition) throws Exception {
        String inspection = idOf(body(inspect("returnItemId", item, "FQC").andExpect(status().isCreated())));
        String path = "/api/v1/inspections/" + inspection;
        as(owner, postJson(path + "/execute", Map.of("results", List.of(
                        Map.of("parameterCode", "DIA", "sampleNumber", 1, "valueNumeric", value)))))
                .andExpect(status().isOk());
        as(owner, postJson(path + "/submit", Map.of())).andExpect(status().isOk());
        as(manager, postJson(path + "/approve", Map.of())).andExpect(status().isOk());
        as(manager, postJson(path + "/disposition", Map.of("disposition", disposition))).andExpect(status().isOk());
        return inspection;
    }

    private void assertAvailable(String quantity) throws Exception {
        as(owner, get("/api/v1/production-batches/" + batch))
                .andExpect(jsonPath("$.data.batch.availableQuantity").value(quantity));
    }

    @Test
    void aPartialReturnLeavesTheRestShippedAndStaysOutOfStock() throws Exception {
        recordReturn("RMA-1", "200")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("RETURN_RECEIVED"))
                .andExpect(jsonPath("$.data.dispatchNumber").isNotEmpty());

        as(owner, get("/api/v1/dispatches/" + dispatch + "/reversal-summary"))
                .andExpect(jsonPath("$.data.status").value("PARTIALLY_REVERSED"))
                .andExpect(jsonPath("$.data.reversedQuantity").value("200.000000"))
                .andExpect(jsonPath("$.data.remainingQuantity").value("800.000000"))
                .andExpect(jsonPath("$.data.pendingQuantity").value("200.000000"));
        assertAvailable("0.000000");
        as(owner, get("/api/v1/dispatches"))
                .andExpect(jsonPath("$.data.items[0].lines[0].quantity").value("1000.000000"))
                .andExpect(jsonPath("$.data.items[0].lines[0].returnedQuantity").value("200.000000"));
    }

    @Test
    void acceptedGoodsReturnToStockOnlyAfterInspection() throws Exception {
        String item = returnItem(recordReturn("RMA-1", "200"));
        assertAvailable("0.000000");
        inspectReturn(item, "20.0", "ACCEPT");
        assertAvailable("200.000000");
        as(owner, get("/api/v1/dispatches/" + dispatch + "/reversals"))
                .andExpect(jsonPath("$.data[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.data[0].items[0].status").value("ACCEPTED"));
        as(owner, get("/api/v1/dispatches/" + dispatch + "/reversal-summary"))
                .andExpect(jsonPath("$.data.acceptedQuantity").value("200.000000"));
    }

    @Test
    void rejectedGoodsNeverReturnToStockAndRaiseAnNcr() throws Exception {
        String item = returnItem(recordReturn("RMA-1", "200"));
        String inspection = inspectReturn(item, "25", "REJECT");
        assertAvailable("0.000000");
        as(owner, get("/api/v1/ncrs").param("inspectionId", inspection))
                .andExpect(jsonPath("$.data.totalItems").value(1));
        as(owner, get("/api/v1/dispatches/" + dispatch + "/reversal-summary"))
                .andExpect(jsonPath("$.data.rejectedQuantity").value("200.000000"));

        // The batch keeps its status: a failed return does not reach goods that were never returned.
        as(owner, get("/api/v1/production-batches/" + batch))
                .andExpect(jsonPath("$.data.batch.status").value("RELEASED"));
    }

    @Test
    void cancellingAnInspectionPutsTheGoodsBackInTheQueue() throws Exception {
        String item = returnItem(recordReturn("RMA-1", "200"));
        String inspection = idOf(body(inspect("returnItemId", item, "FQC").andExpect(status().isCreated())));
        inspect("returnItemId", item, "FQC").andExpect(status().isUnprocessableContent());
        as(owner, postJson("/api/v1/inspections/" + inspection + "/cancel", Map.of())).andExpect(status().isOk());
        inspect("returnItemId", item, "FQC").andExpect(status().isCreated());
    }

    @Test
    void returnedGoodsTakeOnlyAFinalOrOutgoingTemplate() throws Exception {
        String item = returnItem(recordReturn("RMA-1", "200"));
        inspect("returnItemId", item, "IPQC").andExpect(status().isUnprocessableContent());
        inspect("returnItemId", item, "OQC").andExpect(status().isCreated());
    }

    @Test
    void severalPartialReturnsAddUpToAFullReversal() throws Exception {
        recordReturn("RMA-1", "200").andExpect(status().isCreated());
        recordReturn("RMA-2", "300").andExpect(status().isCreated());
        recordReturn("RMA-3", "501").andExpect(status().isUnprocessableContent());
        recordReturn("RMA-3", "500").andExpect(status().isCreated());
        recordReturn("RMA-4", "1").andExpect(status().isUnprocessableContent());

        as(owner, get("/api/v1/dispatches/" + dispatch + "/reversal-summary"))
                .andExpect(jsonPath("$.data.status").value("FULLY_REVERSED"))
                .andExpect(jsonPath("$.data.remainingQuantity").value("0.000000"));
        as(owner, get("/api/v1/dispatches/" + dispatch + "/reversals"))
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    void theSameReturnSentTwiceIsRefused() throws Exception {
        recordReturn("RMA-1", "200").andExpect(status().isCreated());
        recordReturn("RMA-1", "200").andExpect(status().isConflict());
        as(owner, get("/api/v1/dispatches/" + dispatch + "/reversal-summary"))
                .andExpect(jsonPath("$.data.reversedQuantity").value("200.000000"));
    }

    @Test
    void aReturnFollowsItsSerialNumbers() throws Exception {
        String serialBatch = startBatch(product());
        consumeLot(serialBatch, releasedLot("3").id(), "2").andExpect(status().isOk());
        complete(serialBatch, "2").andExpect(status().isOk());
        String first = "S-" + code();
        String second = "S-" + code();
        as(owner, postJson("/api/v1/production-batches/" + serialBatch + "/serials",
                        Map.of("serialNumbers", List.of(first, second))))
                .andExpect(status().isOk());
        inspectBatch(serialBatch, "FQC", "20.0", "ACCEPT");
        String response = body(dispatch(customer(), serialBatch, "2", List.of(first, second)));
        dispatch = idOf(response);
        line = JsonPath.read(response, "$.data.lines[0].id");
        String path = "/api/v1/dispatches/" + dispatch + "/reversals";

        as(owner, postJson(path, returnOf("A", "1", List.of("UNKNOWN")))).andExpect(status().isUnprocessableContent());
        as(owner, postJson(path, returnOf("A", "2", List.of(first)))).andExpect(status().isUnprocessableContent());
        String item = returnItem(as(owner, postJson(path, returnOf("A", "1", List.of(first)))));
        as(owner, postJson(path, returnOf("B", "1", List.of(first)))).andExpect(status().isUnprocessableContent());

        batch = serialBatch;
        inspectReturn(item, "20.0", "ACCEPT");
        dispatch(customer(), serialBatch, "1", List.of(first)).andExpect(status().isCreated());
    }

    @Test
    void returnsShowOnTheTraceAndTheCustomerStaysOnIt() throws Exception {
        recordReturn("RMA-1", "1000").andExpect(status().isCreated());
        as(owner, get("/api/v1/traceability/batches/" + numberOf(batch)))
                .andExpect(jsonPath("$.data.returns[0].returnReference").value("RMA-1"))
                .andExpect(jsonPath("$.data.affectedCustomers[0]").value("Tata Motors"))
                .andExpect(jsonPath("$.data.timeline[*].kind", hasItem("RETURNED")));
    }

    @Test
    void returnsAndMistakeReversalsDoNotMix() throws Exception {
        recordReturn("RMA-1", "1").andExpect(status().isCreated());
        as(owner, postJson("/api/v1/dispatches/" + dispatch + "/reverse", Map.of("reason", "Mistake")))
                .andExpect(status().isUnprocessableContent());

        String other = releasedBatch(releasedLot("5").id(), "5", "5");
        String voided = idOf(body(dispatch(customer(), other, "1", List.of()).andExpect(status().isCreated())));
        as(owner, postJson("/api/v1/dispatches/" + voided + "/reverse", Map.of("reason", "Mistake")))
                .andExpect(status().isOk());
        dispatch = voided;
        recordReturn("RMA-2", "1").andExpect(status().isUnprocessableContent());
    }

    @Test
    void aReturnDateMustBeBetweenDispatchAndToday() throws Exception {
        for (LocalDate date : List.of(LocalDate.now().plusDays(2), LocalDate.now().minusDays(2))) {
            Map<String, Object> request = new HashMap<>(returnOf("RMA-1", "1", List.of()));
            request.put("reversalDate", date.toString());
            as(owner, postJson("/api/v1/dispatches/" + dispatch + "/reversals", request))
                    .andExpect(status().isUnprocessableContent());
        }
    }

    @Test
    void aLineFromAnotherDispatchIsRefused() throws Exception {
        String other = releasedBatch(releasedLot("5").id(), "5", "5");
        line = JsonPath.read(body(dispatch(customer(), other, "1", List.of()).andExpect(status().isCreated())),
                "$.data.lines[0].id");
        recordReturn("RMA-1", "1").andExpect(status().isUnprocessableContent());
    }

    @Test
    void anotherTenantAndViewersCannotReachReturns() throws Exception {
        String id = idOf(body(recordReturn("RMA-1", "1").andExpect(status().isCreated())));
        Session viewer = addMember(owner, "VIEWER");
        as(viewer, postJson("/api/v1/dispatches/" + dispatch + "/reversals", returnOf("RMA-2", "1", List.of())))
                .andExpect(status().isForbidden());

        Session stranger = register();
        as(stranger, get("/api/v1/dispatch-reversals/" + id)).andExpect(status().isNotFound());
        as(stranger, get("/api/v1/dispatch-reversals")).andExpect(jsonPath("$.data.totalItems").value(0));
        as(stranger, get("/api/v1/dispatches/" + dispatch + "/reversals")).andExpect(status().isNotFound());
        as(stranger, get("/api/v1/dispatches/" + dispatch + "/reversal-summary")).andExpect(status().isNotFound());
        as(stranger, postJson("/api/v1/dispatches/" + dispatch + "/reversals", returnOf("RMA-3", "1", List.of())))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsAndDispatchesCannotBeDeletedOrAltered() throws Exception {
        String item = returnItem(recordReturn("RMA-1", "200"));
        inspectReturn(item, "20.0", "ACCEPT");

        List<String> statements = new ArrayList<>(List.of(
                "DELETE FROM dispatch_reversal_items",
                "DELETE FROM dispatch_reversals",
                "DELETE FROM dispatch_records",
                "UPDATE dispatch_reversal_items SET quantity = 1",
                "UPDATE dispatch_reversals SET reason = 'Rewritten'"));
        for (String sql : statements) {
            assertThatThrownBy(() -> asAppRole(owner.tenantId(), statement -> statement.update(sql)))
                    .as(sql).isInstanceOf(DataAccessException.class);
        }
    }
}
