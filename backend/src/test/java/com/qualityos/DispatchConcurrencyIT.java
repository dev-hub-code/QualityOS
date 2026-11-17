package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * A dispatch and the start of an outgoing inspection, sent at the same moment, against the real database. Whichever
 * takes the batch's lock first wins; the other sees what it did. An OQC always covers exactly what is left to ship.
 */
class DispatchConcurrencyIT extends TraceabilityFixture {

    private static final int ROUNDS = 20;

    @Test
    void aDispatchAndAnOutgoingInspectionNeverPassEachOther() throws Exception {
        // More batches than a trial's plant limit allows.
        asDatabaseSuperuser().update("""
                UPDATE subscriptions SET plan_id = (SELECT id FROM subscription_plans WHERE code = 'ENTERPRISE')
                WHERE tenant_id = ?
                """, owner.tenantId());
        String customer = customer();
        String template = template("OQC");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int dispatchedFirst = 0;
        int inspectedFirst = 0;
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String batch = releasedBatch(releasedLot("10").id(), "10", "10");
                CyclicBarrier together = new CyclicBarrier(2);
                Future<MockHttpServletResponse> dispatch = pool.submit(at(together, () -> as(owner,
                        postJson("/api/v1/dispatches", java.util.Map.of("customerId", customer, "dispatchedOn", TODAY,
                                "items", List.of(java.util.Map.of("productionBatchId", batch, "quantity", "4",
                                        "serialNumbers", List.of())))))
                        .andReturn().getResponse()));
                Future<MockHttpServletResponse> oqc = pool.submit(at(together, () -> as(owner,
                        postJson("/api/v1/inspections", java.util.Map.of("productionBatchId", batch,
                                "templateId", template)))
                        .andReturn().getResponse()));

                MockHttpServletResponse inspection = oqc.get();
                assertThat(inspection.getStatus()).as("the OQC starts either way: %s", inspection.getContentAsString()).isEqualTo(201);
                String id = JsonPath.read(inspection.getContentAsString(), "$.data.id");
                BigDecimal covered = new BigDecimal(JsonPath.<String>read(
                        body(as(owner, get("/api/v1/inspections/" + id))), "$.data.quantity"));
                int shipped = dispatch.get().getStatus();
                if (shipped == 201) {
                    // The dispatch took the lock first; the OQC covers only what was left.
                    assertThat(covered).isEqualByComparingTo("6");
                    dispatchedFirst++;
                } else {
                    // The OQC took it first; the dispatch waits for its decision.
                    assertThat(shipped).isEqualTo(422);
                    assertThat(dispatch.get().getContentAsString()).contains("is in outgoing inspection");
                    assertThat(covered).isEqualByComparingTo("10");
                    inspectedFirst++;
                }
                String available = JsonPath.read(body(as(owner, get("/api/v1/production-batches/" + batch))
                        .andExpect(status().isOk())), "$.data.batch.availableQuantity");
                assertThat(new BigDecimal(available)).isEqualByComparingTo(covered);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(dispatchedFirst + inspectedFirst).isEqualTo(ROUNDS);
    }

    /** Waits for the other request, so both are sent together. */
    private static <T> Callable<T> at(CyclicBarrier together, Callable<T> request) {
        return () -> {
            together.await();
            return request.call();
        };
    }
}
