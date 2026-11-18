package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.qualityos.subscription.service.CashfreeClient;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

/** Cashfree's calls are stubbed. Its real signature check runs, and CashfreeClientTest covers the HTTP. */
@TestPropertySource(properties = {
    "qualityos.payments.cashfree.client-id=test-app",
    "qualityos.payments.cashfree.client-secret=test-secret"
})
class CashfreePaymentsIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/subscription";
    private static final String WEBHOOK = "/api/v1/public/webhooks/cashfree";
    private static final Map<String, String> PHONE = Map.of("phone", "9876543210");

    @MockitoSpyBean
    private CashfreeClient cashfree;

    private Session owner;
    private String invoice;
    private BigDecimal total;

    @BeforeEach
    void setUp() throws Exception {
        owner = register();
        clearInvocations(cashfree);
        chooseStarter(owner, "MONTHLY");
    }

    private void chooseStarter(Session session, String cycle) throws Exception {
        String plan = asDatabaseSuperuser().queryForObject(
                "SELECT id::text FROM subscription_plans WHERE code = 'STARTER'", String.class);
        String created = as(session, postJson(BASE + "/plan", Map.of("planId", plan, "cycle", cycle)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        invoice = JsonPath.read(created, "$.data.id");
        total = new BigDecimal(JsonPath.read(created, "$.data.total").toString());
    }

    /** Opens an order through the API and leaves Cashfree holding it, and returns its id. */
    private String checkout() throws Exception {
        doReturn(new CashfreeClient.Order("ignored", "ACTIVE", "session_1")).when(cashfree).createOrder(any());
        return JsonPath.read(as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentSessionId").value("session_1"))
                .andExpect(jsonPath("$.data.mode").value("sandbox"))
                .andReturn().getResponse().getContentAsString(), "$.data.orderId");
    }

    private void cashfreeHolds(String order, CashfreeClient.Payment... payments) {
        doReturn(List.of(payments)).when(cashfree).payments(order);
    }

    private CashfreeClient.Payment payment(String status, BigDecimal amount) {
        return new CashfreeClient.Payment(UUID.randomUUID().toString(), status, amount, "INR", "upi", "Simulated");
    }

    private ResultActions webhook(String order, String secret) throws Exception {
        byte[] body = ("{\"type\":\"PAYMENT_SUCCESS_WEBHOOK\",\"data\":{\"order\":{\"order_id\":\"" + order + "\"}}}")
                .getBytes(StandardCharsets.UTF_8);
        String timestamp = "1750000000000";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
        return mvc.perform(post(WEBHOOK).content(body).contentType("application/json")
                .header("x-webhook-timestamp", timestamp)
                .header("x-webhook-signature", Base64.getEncoder().encodeToString(mac.doFinal(body))));
    }

    private String invoiceStatus() {
        return asDatabaseSuperuser().queryForObject(
                "SELECT status FROM subscription_invoices WHERE id = ?::uuid", String.class, invoice);
    }

    private long payments(String status) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM payment_transactions WHERE invoice_id = ?::uuid AND status = ?", Long.class,
                invoice, status);
    }

    private String orderStatus(String order) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT status FROM gateway_orders WHERE gateway_order_id = ?", String.class, order);
    }

    private long audited(String action) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM audit_logs WHERE tenant_id = ? AND action = ?", Long.class, owner.tenantId(), action);
    }

    @Test
    void theGatewayShowsAsAvailable() throws Exception {
        as(owner, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BASE + "/payment-gateway"))
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.mode").value("sandbox"));
    }

    @Test
    void theAmountComesFromTheInvoiceAndAnOpenOrderIsContinued() throws Exception {
        String order = checkout();

        ArgumentCaptor<CashfreeClient.NewOrder> sent = ArgumentCaptor.forClass(CashfreeClient.NewOrder.class);
        verify(cashfree).createOrder(sent.capture());
        assertThat(sent.getValue().amount()).isEqualByComparingTo(total);
        assertThat(sent.getValue().orderId()).isEqualTo(order).matches("[A-Za-z0-9_-]{3,45}");
        assertThat(sent.getValue().customer().phone()).isEqualTo("9876543210");
        assertThat(sent.getValue().customer().id()).matches("[A-Za-z0-9]{3,50}");
        assertThat(audited("PAYMENT_CHECKOUT_STARTED")).isEqualTo(1);

        doReturn(java.util.Optional.of(new CashfreeClient.Order(order, "ACTIVE", "session_2")))
                .when(cashfree).findOrder(order);
        as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE))
                .andExpect(jsonPath("$.data.orderId").value(order))
                .andExpect(jsonPath("$.data.paymentSessionId").value("session_2"));
        verify(cashfree, times(1)).createOrder(any());
    }

    @Test
    void aBadPhoneIsRefusedBeforeCashfreeIsCalled() throws Exception {
        as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", Map.of("phone", "12345")))
                .andExpect(status().isBadRequest());
        verify(cashfree, never()).createOrder(any());
    }

    @Test
    void anOrdersOrganisationIsFoundOnlyBeforeAnyOrganisationIsKnown() throws Exception {
        String order = checkout();
        String lookup = "SELECT gateway_order_tenant('CASHFREE', ?)";
        java.util.List<Object> found = new java.util.ArrayList<>();
        asAppRole(null, jdbc -> found.add(jdbc.queryForObject(lookup, java.util.UUID.class, order)));
        asAppRole(java.util.UUID.randomUUID(), jdbc -> found.add(jdbc.queryForObject(lookup, java.util.UUID.class, order)));
        asAppRole(owner.tenantId(), jdbc -> found.add(jdbc.queryForObject(lookup, java.util.UUID.class, order)));
        assertThat(found).containsExactly(owner.tenantId(), null, null);
    }

    @Test
    void aSignedWebhookPaysTheInvoiceOnceHoweverOftenItArrives() throws Exception {
        String order = checkout();
        cashfreeHolds(order, payment("SUCCESS", total));

        webhook(order, "test-secret").andExpect(status().isOk());
        webhook(order, "test-secret").andExpect(status().isOk());

        assertThat(invoiceStatus()).isEqualTo("PAID");
        assertThat(orderStatus(order)).isEqualTo("PAID");
        assertThat(payments("SUCCEEDED")).isEqualTo(1);
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT provider FROM payment_transactions WHERE invoice_id = ?::uuid", String.class, invoice))
                .isEqualTo("CASHFREE");
        assertThat(audited("PAYMENT_RECORDED")).isEqualTo(1);
    }

    @Test
    void aForgedWebhookChangesNothingAndIsNotBelieved() throws Exception {
        String order = checkout();
        cashfreeHolds(order, payment("SUCCESS", total));

        webhook(order, "wrong-secret").andExpect(status().isUnauthorized());
        mvc.perform(post(WEBHOOK).content("{}").contentType("application/json")).andExpect(status().isUnauthorized());

        verify(cashfree, never()).payments(anyString());
        assertThat(invoiceStatus()).isEqualTo("ISSUED");
    }

    @Test
    void aWebhookSayingPaidMeansNothingUntilCashfreeConfirmsIt() throws Exception {
        String order = checkout();
        cashfreeHolds(order);

        webhook(order, "test-secret").andExpect(status().isOk());

        assertThat(invoiceStatus()).isEqualTo("ISSUED");
        assertThat(orderStatus(order)).isEqualTo("ACTIVE");
    }

    @Test
    void anOrderThatIsNotOursIsAcknowledgedAndIgnored() throws Exception {
        webhook("SOMEONE-ELSE", "test-secret").andExpect(status().isOk());
        verify(cashfree, never()).payments(anyString());
    }

    @Test
    void returningFromCheckoutAsksCashfreeWhatHappened() throws Exception {
        String order = checkout();
        cashfreeHolds(order);
        as(owner, postJson(BASE + "/gateway-orders/" + order + "/confirm", Map.of()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        assertThat(invoiceStatus()).isEqualTo("ISSUED");

        cashfreeHolds(order, payment("SUCCESS", total));
        as(owner, postJson(BASE + "/gateway-orders/" + order + "/confirm", Map.of()))
                .andExpect(jsonPath("$.data.status").value("PAID"));
        assertThat(invoiceStatus()).isEqualTo("PAID");
    }

    @Test
    void anotherOrganisationCannotConfirmOrPayThisOrder() throws Exception {
        String order = checkout();
        Session other = register();
        clearInvocations(cashfree);

        as(other, postJson(BASE + "/gateway-orders/" + order + "/confirm", Map.of())).andExpect(status().isNotFound());
        as(other, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE)).andExpect(status().isNotFound());

        verify(cashfree, never()).payments(anyString());
    }

    @Test
    void aFailedAttemptIsRecordedAndTheInvoiceStaysOpenForTheNextOne() throws Exception {
        String order = checkout();
        CashfreeClient.Payment failed = payment("FAILED", total);
        cashfreeHolds(order, failed, payment("USER_DROPPED", total), payment("PENDING", total));

        webhook(order, "test-secret").andExpect(status().isOk());
        assertThat(invoiceStatus()).isEqualTo("ISSUED");
        assertThat(payments("FAILED")).isEqualTo(1);

        cashfreeHolds(order, failed, payment("SUCCESS", total));
        webhook(order, "test-secret").andExpect(status().isOk());
        assertThat(invoiceStatus()).isEqualTo("PAID");
        assertThat(payments("FAILED")).isEqualTo(1);
        assertThat(payments("SUCCEEDED")).isEqualTo(1);
    }

    private String checkoutBody() throws Exception {
        return as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE))
                .andReturn().getResponse().getContentAsString();
    }

    private List<String> orderStatuses() {
        return asDatabaseSuperuser().queryForList(
                "SELECT status FROM gateway_orders WHERE invoice_id = ?::uuid ORDER BY created_at", String.class, invoice);
    }

    @Test
    void twoCheckoutsAtTheSameMomentShareOneOrder() throws Exception {
        // Cashfree takes a while to create the order, long enough for the second request to arrive meanwhile.
        org.mockito.Mockito.doAnswer(call -> {
            Thread.sleep(400);
            return new CashfreeClient.Order("ignored", "ACTIVE", "session_1");
        }).when(cashfree).createOrder(any());
        // By the time the second request asks, Cashfree holds the first one's order.
        org.mockito.Mockito.doAnswer(call -> java.util.Optional.of(
                new CashfreeClient.Order(call.getArgument(0), "ACTIVE", "session_1"))).when(cashfree).findOrder(anyString());

        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> click = () -> {
                go.await();
                return checkoutBody();
            };
            var first = pool.submit(click);
            var second = pool.submit(click);
            go.countDown();
            List<String> bodies = List.of(first.get(), second.get());

            List<String> orderIds = bodies.stream().map(body -> JsonPath.<String>read(body, "$.data.orderId")).toList();
            assertThat(orderIds).doesNotContainNull();
            assertThat(orderIds.get(0)).isEqualTo(orderIds.get(1));
        } finally {
            pool.shutdownNow();
        }
        verify(cashfree, times(1)).createOrder(any());
        assertThat(orderStatuses()).containsExactly("ACTIVE");
        assertThat(audited("PAYMENT_CHECKOUT_STARTED")).isEqualTo(1);
    }

    @Test
    void aCheckoutThatStoppedHalfwayIsCarriedOnNotRepeated() throws Exception {
        // Cashfree created the order, but the answer never came back, and asking again failed too.
        org.mockito.Mockito.doThrow(new com.qualityos.common.exception.QualityOsException(
                        com.qualityos.common.exception.ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "No answer"))
                .when(cashfree).createOrder(any());
        org.mockito.Mockito.doThrow(new com.qualityos.common.exception.QualityOsException(
                        com.qualityos.common.exception.ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "No answer"))
                .when(cashfree).findOrder(anyString());
        as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE)).andExpect(status().is5xxServerError());
        assertThat(orderStatuses()).containsExactly("CREATING");

        // The next checkout finds it at Cashfree and carries on with it.
        org.mockito.Mockito.doAnswer(call -> java.util.Optional.of(
                new CashfreeClient.Order(call.getArgument(0), "ACTIVE", "session_9"))).when(cashfree).findOrder(anyString());
        as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentSessionId").value("session_9"));
        verify(cashfree, times(1)).createOrder(any());
        assertThat(orderStatuses()).containsExactly("ACTIVE");
    }

    @Test
    void anOrderCashfreeNeverCreatedIsSetAsideAfterAWhile() throws Exception {
        org.mockito.Mockito.doThrow(new com.qualityos.common.exception.QualityOsException(
                        com.qualityos.common.exception.ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "No answer"))
                .when(cashfree).createOrder(any());
        org.mockito.Mockito.doThrow(new com.qualityos.common.exception.QualityOsException(
                        com.qualityos.common.exception.ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "No answer"))
                .when(cashfree).findOrder(anyString());
        as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE)).andExpect(status().is5xxServerError());

        // Cashfree does not have it, but it is too soon to be sure it is not still being made.
        doReturn(java.util.Optional.empty()).when(cashfree).findOrder(anyString());
        as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message")
                        .value("A payment for this invoice is being started. Try again in a moment."));

        // Later, it is set aside and a new order made.
        asDatabaseSuperuser().update(
                "UPDATE gateway_orders SET created_at = now() - INTERVAL '3 minutes' WHERE invoice_id = ?::uuid", invoice);
        doReturn(new CashfreeClient.Order("ignored", "ACTIVE", "session_2")).when(cashfree).createOrder(any());
        as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentSessionId").value("session_2"));
        assertThat(orderStatuses()).containsExactly("ABANDONED", "ACTIVE");
    }

    @Test
    void aFailedCreateThatCashfreeNeverSawDoesNotHoldUpTheNextTry() throws Exception {
        org.mockito.Mockito.doThrow(new com.qualityos.common.exception.QualityOsException(
                        com.qualityos.common.exception.ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "No answer"))
                .when(cashfree).createOrder(any());
        doReturn(java.util.Optional.empty()).when(cashfree).findOrder(anyString());
        as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE)).andExpect(status().is5xxServerError());
        assertThat(orderStatuses()).containsExactly("ABANDONED");

        doReturn(new CashfreeClient.Order("ignored", "ACTIVE", "session_3")).when(cashfree).createOrder(any());
        as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE)).andExpect(status().isOk());
        assertThat(orderStatuses()).containsExactly("ABANDONED", "ACTIVE");
    }

    @Test
    void aPaymentWithoutAnAmountIsKeptForAPerson() throws Exception {
        String order = checkout();
        cashfreeHolds(order, payment("SUCCESS", null));
        webhook(order, "test-secret").andExpect(status().isOk());
        assertThat(invoiceStatus()).isEqualTo("ISSUED");
        assertThat(orderStatus(order)).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void aPaymentInAnotherCurrencyIsKeptForAPerson() throws Exception {
        String order = checkout();
        cashfreeHolds(order, new CashfreeClient.Payment("p-usd", "SUCCESS", total, "USD", "card", "Simulated"));
        webhook(order, "test-secret").andExpect(status().isOk());
        assertThat(invoiceStatus()).isEqualTo("ISSUED");
        assertThat(orderStatus(order)).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void moneyReachingAnAbandonedOrderIsStillApplied() throws Exception {
        String order = checkout();
        asDatabaseSuperuser().update("UPDATE gateway_orders SET status = 'ABANDONED' WHERE gateway_order_id = ?", order);
        cashfreeHolds(order, payment("SUCCESS", total));
        webhook(order, "test-secret").andExpect(status().isOk());
        assertThat(invoiceStatus()).isEqualTo("PAID");
        assertThat(orderStatus(order)).isEqualTo("PAID");
    }

    @Test
    void aWrongAmountDoesNotPayTheInvoiceAndIsKeptForAPerson() throws Exception {
        String order = checkout();
        cashfreeHolds(order, payment("SUCCESS", BigDecimal.ONE));

        webhook(order, "test-secret").andExpect(status().isOk());

        assertThat(invoiceStatus()).isEqualTo("ISSUED");
        assertThat(orderStatus(order)).isEqualTo("NEEDS_REVIEW");
        assertThat(audited("PAYMENT_NEEDS_REVIEW")).isEqualTo(1);
    }

    @Test
    void moneyArrivingAfterTheInvoiceWasReplacedIsKeptForAPerson() throws Exception {
        String order = checkout();
        String first = invoice;
        chooseStarter(owner, "ANNUAL");
        cashfreeHolds(order, payment("SUCCESS", total));

        as(owner, postJson(BASE + "/gateway-orders/" + order + "/confirm", Map.of()))
                .andExpect(jsonPath("$.data.status").value("NEEDS_REVIEW"));

        invoice = first;
        assertThat(invoiceStatus()).isEqualTo("VOID");
        assertThat(audited("PAYMENT_NEEDS_REVIEW")).isEqualTo(1);
    }

    @Test
    void aPaidInvoiceCannotBeCheckedOutAgain() throws Exception {
        String order = checkout();
        cashfreeHolds(order, payment("SUCCESS", total));
        webhook(order, "test-secret").andExpect(status().isOk());

        as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE)).andExpect(status().isConflict());
    }

    private ResultActions refundWebhook(String order, String refundId) throws Exception {
        byte[] body = ("{\"type\":\"REFUND_STATUS_WEBHOOK\",\"data\":{\"refund\":{\"order_id\":\"" + order
                + "\",\"refund_id\":\"" + refundId + "\"}}}").getBytes(StandardCharsets.UTF_8);
        String timestamp = "1750000000000";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
        return mvc.perform(post(WEBHOOK).content(body).contentType("application/json")
                .header("x-webhook-timestamp", timestamp)
                .header("x-webhook-signature", Base64.getEncoder().encodeToString(mac.doFinal(body))));
    }

    private Session operator() throws Exception {
        Session admin = register();
        makeOperator(admin, "SUPER_ADMIN");
        return admin;
    }

    @Test
    void aCreditNoteOnAGatewayPaymentIsRefundedThroughTheGatewayUntilItSaysItWentThrough() throws Exception {
        String order = checkout();
        cashfreeHolds(order, payment("SUCCESS", total));
        webhook(order, "test-secret").andExpect(status().isOk());
        assertThat(invoiceStatus()).isEqualTo("PAID");
        doAnswer(call -> new CashfreeClient.GatewayRefund(call.getArgument(1), "cf-1", "PENDING", null))
                .when(cashfree).refund(eq(order), anyString(), any(), anyString());

        Session admin = operator();
        BigDecimal amount = asDatabaseSuperuser().queryForObject(
                "SELECT amount FROM subscription_invoices WHERE id = ?::uuid", BigDecimal.class, invoice);
        String created = as(admin, postJson("/api/v1/platform/organisations/" + owner.tenantId() + "/invoices/" + invoice
                        + "/credit-notes", Map.of("amount", amount.toPlainString(), "reason", "Cancelled in the first week",
                        "refundBy", "GATEWAY", "endAccess", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.refund.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        ArgumentCaptor<BigDecimal> refunded = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> refundId = ArgumentCaptor.forClass(String.class);
        verify(cashfree).refund(eq(order), refundId.capture(), refunded.capture(), anyString());
        assertThat(refunded.getValue()).isEqualByComparingTo(total);
        assertThat(refundId.getValue()).matches("[A-Za-z0-9]{3,40}");
        assertThat((String) JsonPath.read(created, "$.data.refund.reference")).isEqualTo(refundId.getValue());

        doReturn(new CashfreeClient.GatewayRefund(refundId.getValue(), "cf-1", "SUCCESS", null))
                .when(cashfree).findRefund(order, refundId.getValue());
        refundWebhook(order, refundId.getValue()).andExpect(status().isOk());
        as(owner, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BASE + "/credit-notes"))
                .andExpect(jsonPath("$.data[0].refund.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].refund.refundedOn").isNotEmpty());
        assertThat(audited("REFUND_SUCCEEDED")).isEqualTo(1);
        // Replayed, it changes nothing.
        refundWebhook(order, refundId.getValue()).andExpect(status().isOk());
        assertThat(audited("REFUND_SUCCEEDED")).isEqualTo(1);
    }

    @Test
    void aPaymentNoInvoiceCouldTakeIsListedAndRefundedWhole() throws Exception {
        String order = checkout();
        cashfreeHolds(order, payment("SUCCESS", null));
        webhook(order, "test-secret").andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("NEEDS_REVIEW");
        doAnswer(call -> new CashfreeClient.GatewayRefund(call.getArgument(1), "cf-2", "SUCCESS", null))
                .when(cashfree).refund(eq(order), anyString(), any(), anyString());

        Session admin = operator();
        String listed = as(admin, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/platform/unapplied-payments")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        List<String> ids = JsonPath.read(listed, "$.data[?(@.gatewayOrderId == '" + order + "')].orderId");
        assertThat(ids).hasSize(1);
        String refund = "/api/v1/platform/organisations/" + owner.tenantId() + "/gateway-orders/" + ids.getFirst()
                + "/refund";
        as(admin, postJson(refund, Map.of("reason", "Paid twice"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        assertThat(orderStatus(order)).isEqualTo("REFUNDED");
        verify(cashfree).refund(eq(order), anyString(), argThat(value -> value.compareTo(total) == 0), anyString());
        as(admin, postJson(refund, Map.of("reason", "Again"))).andExpect(status().isConflict());
        assertThat(audited("PAYMENT_REFUNDED")).isEqualTo(1);
    }
}
