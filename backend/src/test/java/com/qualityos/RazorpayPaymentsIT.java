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
import com.qualityos.subscription.service.RazorpayClient;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

/** Razorpay's calls are stubbed. Its real signature check runs, and RazorpayClientTest covers the HTTP. */
@TestPropertySource(properties = {
    "qualityos.payments.razorpay.key-id=rzp_test_public",
    "qualityos.payments.razorpay.key-secret=key-secret",
    "qualityos.payments.razorpay.webhook-secret=test-secret",
    // Anything not stubbed fails at once rather than reaching Razorpay.
    "qualityos.payments.razorpay.base-url=http://127.0.0.1:9/v1"
})
class RazorpayPaymentsIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/subscription";
    private static final String WEBHOOK = "/api/v1/public/webhooks/razorpay";
    private static final Map<String, String> PHONE = Map.of("phone", "9876543210");

    @MockitoSpyBean
    private RazorpayClient razorpay;

    private Session owner;
    private String invoice;
    private BigDecimal total;

    @BeforeEach
    void setUp() throws Exception {
        owner = register();
        clearInvocations(razorpay);
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

    /** The order Razorpay would hold under our reference. */
    private RazorpayClient.Order held(String reference) {
        return new RazorpayClient.Order("order_" + reference, reference, "created", total);
    }

    /** Opens an order through the API and leaves Razorpay holding it, and returns our reference for it. */
    private String checkout() throws Exception {
        doAnswer(call -> held(call.getArgument(0))).when(razorpay).createOrder(anyString(), any(), anyString());
        return JsonPath.read(as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gatewayOrderId").isNotEmpty())
                .andExpect(jsonPath("$.data.mode").value("sandbox"))
                .andReturn().getResponse().getContentAsString(), "$.data.orderId");
    }

    private void razorpayHolds(String order, RazorpayClient.Payment... payments) {
        doReturn(List.of(payments)).when(razorpay).payments(order);
    }

    private RazorpayClient.Payment payment(String status, BigDecimal amount) {
        return new RazorpayClient.Payment(UUID.randomUUID().toString(), status, amount, "INR", "upi", "Simulated");
    }

    /** Razorpay's order.paid event, which names our reference as the order's receipt. */
    private ResultActions webhook(String order, String secret) throws Exception {
        return signed(("{\"event\":\"order.paid\",\"payload\":{\"order\":{\"entity\":{\"id\":\"order_" + order
                + "\",\"receipt\":\"" + order + "\"}}}}").getBytes(StandardCharsets.UTF_8), secret);
    }

    private ResultActions signed(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mvc.perform(post(WEBHOOK).content(body).contentType("application/json")
                .header("x-razorpay-signature", java.util.HexFormat.of().formatHex(mac.doFinal(body))));
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

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(razorpay).createOrder(eq(order), amount.capture(), anyString());
        assertThat(amount.getValue()).isEqualByComparingTo(total);
        // The receipt Razorpay keeps is at most 40 characters.
        assertThat(order).matches("[A-Za-z0-9_-]{3,40}");
        assertThat(audited("PAYMENT_CHECKOUT_STARTED")).isEqualTo(1);

        doReturn(java.util.Optional.of(held(order))).when(razorpay).findOrder(order);
        as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", Map.of()))
                .andExpect(jsonPath("$.data.orderId").value(order))
                .andExpect(jsonPath("$.data.gatewayOrderId").value("order_" + order));
        verify(razorpay, times(1)).createOrder(anyString(), any(), anyString());
    }

    @Test
    void theBrowserGetsThePublicKeyAndAmountInPaiseButNeverASecret() throws Exception {
        doAnswer(call -> held(call.getArgument(0))).when(razorpay).createOrder(anyString(), any(), anyString());
        String body = as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", Map.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keyId").value("rzp_test_public"))
                .andExpect(jsonPath("$.data.amount").value(total.movePointRight(2).longValueExact()))
                .andExpect(jsonPath("$.data.currency").value("INR"))
                .andExpect(jsonPath("$.data.customerEmail").value(owner.email().toLowerCase(java.util.Locale.ROOT)))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("key-secret").doesNotContain("test-secret");
    }

    @Test
    void anOrdersOrganisationIsFoundOnlyBeforeAnyOrganisationIsKnown() throws Exception {
        String order = checkout();
        String lookup = "SELECT gateway_order_tenant('RAZORPAY', ?)";
        java.util.List<Object> found = new java.util.ArrayList<>();
        asAppRole(null, jdbc -> found.add(jdbc.queryForObject(lookup, java.util.UUID.class, order)));
        asAppRole(java.util.UUID.randomUUID(), jdbc -> found.add(jdbc.queryForObject(lookup, java.util.UUID.class, order)));
        asAppRole(owner.tenantId(), jdbc -> found.add(jdbc.queryForObject(lookup, java.util.UUID.class, order)));
        assertThat(found).containsExactly(owner.tenantId(), null, null);
    }

    @Test
    void aSignedWebhookPaysTheInvoiceOnceHoweverOftenItArrives() throws Exception {
        String order = checkout();
        razorpayHolds(order, payment("SUCCESS", total));

        webhook(order, "test-secret").andExpect(status().isOk());
        webhook(order, "test-secret").andExpect(status().isOk());

        assertThat(invoiceStatus()).isEqualTo("PAID");
        assertThat(orderStatus(order)).isEqualTo("PAID");
        assertThat(payments("SUCCEEDED")).isEqualTo(1);
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT provider FROM payment_transactions WHERE invoice_id = ?::uuid", String.class, invoice))
                .isEqualTo("RAZORPAY");
        assertThat(audited("PAYMENT_RECORDED")).isEqualTo(1);
    }

    @Test
    void aPaymentEventNamingOnlyRazorpaysOrderIsTracedBackToOurs() throws Exception {
        String order = checkout();
        razorpayHolds(order, payment("SUCCESS", total));
        doReturn(held(order)).when(razorpay).order("order_" + order);

        signed(("{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_1\","
                + "\"order_id\":\"order_" + order + "\"}}}}").getBytes(StandardCharsets.UTF_8), "test-secret")
                .andExpect(status().isOk());

        assertThat(invoiceStatus()).isEqualTo("PAID");
    }

    @Test
    void aPaymentLeftAuthorisedIsCapturedForTheOrderAmountThenApplied() throws Exception {
        String order = checkout();
        RazorpayClient.Payment authorised = new RazorpayClient.Payment("pay_a", "AUTHORIZED", total, "INR", "card", null);
        RazorpayClient.Payment captured = new RazorpayClient.Payment("pay_a", "SUCCESS", total, "INR", "card", null);
        doReturn(List.of(authorised)).doReturn(List.of(captured)).when(razorpay).payments(order);
        doReturn(true).when(razorpay).capture("pay_a", total);

        webhook(order, "test-secret").andExpect(status().isOk());

        verify(razorpay).capture(eq("pay_a"), argThat(value -> value.compareTo(total) == 0));
        assertThat(invoiceStatus()).isEqualTo("PAID");
    }

    @Test
    void aPaymentAuthorisedForAnotherAmountIsNeverCaptured() throws Exception {
        String order = checkout();
        razorpayHolds(order, new RazorpayClient.Payment("pay_b", "AUTHORIZED", BigDecimal.ONE, "INR", "card", null));

        webhook(order, "test-secret").andExpect(status().isOk());

        verify(razorpay, never()).capture(anyString(), any());
        assertThat(invoiceStatus()).isEqualTo("ISSUED");
    }

    @Test
    void aForgedWebhookChangesNothingAndIsNotBelieved() throws Exception {
        String order = checkout();
        razorpayHolds(order, payment("SUCCESS", total));

        webhook(order, "wrong-secret").andExpect(status().isUnauthorized());
        mvc.perform(post(WEBHOOK).content("{}").contentType("application/json")).andExpect(status().isUnauthorized());

        verify(razorpay, never()).payments(anyString());
        assertThat(invoiceStatus()).isEqualTo("ISSUED");
    }

    @Test
    void aWebhookSayingPaidMeansNothingUntilRazorpayConfirmsIt() throws Exception {
        String order = checkout();
        razorpayHolds(order);

        webhook(order, "test-secret").andExpect(status().isOk());

        assertThat(invoiceStatus()).isEqualTo("ISSUED");
        assertThat(orderStatus(order)).isEqualTo("ACTIVE");
    }

    @Test
    void anOrderThatIsNotOursIsAcknowledgedAndIgnored() throws Exception {
        webhook("SOMEONE-ELSE", "test-secret").andExpect(status().isOk());
        verify(razorpay, never()).payments(anyString());
    }

    @Test
    void returningFromCheckoutAsksRazorpayWhatHappened() throws Exception {
        String order = checkout();
        razorpayHolds(order);
        as(owner, postJson(BASE + "/gateway-orders/" + order + "/confirm", Map.of()))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        assertThat(invoiceStatus()).isEqualTo("ISSUED");

        razorpayHolds(order, payment("SUCCESS", total));
        as(owner, postJson(BASE + "/gateway-orders/" + order + "/confirm", Map.of()))
                .andExpect(jsonPath("$.data.status").value("PAID"));
        assertThat(invoiceStatus()).isEqualTo("PAID");
        // The owner who paid is usually the only billing manager, so the receipt must reach them too.
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM notifications WHERE tenant_id = ? AND user_id = ? AND type = 'PAYMENT_RECEIVED'",
                Integer.class, owner.tenantId(), owner.userId())).isEqualTo(1);
    }

    @Test
    void anotherOrganisationCannotConfirmOrPayThisOrder() throws Exception {
        String order = checkout();
        Session other = register();
        clearInvocations(razorpay);

        as(other, postJson(BASE + "/gateway-orders/" + order + "/confirm", Map.of())).andExpect(status().isNotFound());
        as(other, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE)).andExpect(status().isNotFound());

        verify(razorpay, never()).payments(anyString());
    }

    @Test
    void aFailedAttemptIsRecordedAndTheInvoiceStaysOpenForTheNextOne() throws Exception {
        String order = checkout();
        RazorpayClient.Payment failed = payment("FAILED", total);
        razorpayHolds(order, failed, payment("USER_DROPPED", total), payment("PENDING", total));

        webhook(order, "test-secret").andExpect(status().isOk());
        assertThat(invoiceStatus()).isEqualTo("ISSUED");
        assertThat(payments("FAILED")).isEqualTo(1);

        razorpayHolds(order, failed, payment("SUCCESS", total));
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
        // Razorpay takes a while to create the order, long enough for the second request to arrive meanwhile.
        org.mockito.Mockito.doAnswer(call -> {
            Thread.sleep(400);
            return held(call.getArgument(0));
        }).when(razorpay).createOrder(anyString(), any(), anyString());
        // By the time the second request asks, Razorpay holds the first one's order.
        org.mockito.Mockito.doAnswer(call -> java.util.Optional.of(
                held(call.getArgument(0)))).when(razorpay).findOrder(anyString());

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
        verify(razorpay, times(1)).createOrder(anyString(), any(), anyString());
        assertThat(orderStatuses()).containsExactly("ACTIVE");
        assertThat(audited("PAYMENT_CHECKOUT_STARTED")).isEqualTo(1);
    }

    @Test
    void aCheckoutThatStoppedHalfwayIsCarriedOnNotRepeated() throws Exception {
        // Razorpay created the order, but the answer never came back, and asking again failed too.
        org.mockito.Mockito.doThrow(new com.qualityos.common.exception.QualityOsException(
                        com.qualityos.common.exception.ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "No answer"))
                .when(razorpay).createOrder(anyString(), any(), anyString());
        org.mockito.Mockito.doThrow(new com.qualityos.common.exception.QualityOsException(
                        com.qualityos.common.exception.ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "No answer"))
                .when(razorpay).findOrder(anyString());
        as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE)).andExpect(status().is5xxServerError());
        assertThat(orderStatuses()).containsExactly("CREATING");

        // The next checkout finds it at Razorpay and carries on with it.
        org.mockito.Mockito.doAnswer(call -> java.util.Optional.of(
                held(call.getArgument(0)))).when(razorpay).findOrder(anyString());
        as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gatewayOrderId").isNotEmpty());
        verify(razorpay, times(1)).createOrder(anyString(), any(), anyString());
        assertThat(orderStatuses()).containsExactly("ACTIVE");
    }

    @Test
    void anOrderRazorpayNeverCreatedIsSetAsideAfterAWhile() throws Exception {
        org.mockito.Mockito.doThrow(new com.qualityos.common.exception.QualityOsException(
                        com.qualityos.common.exception.ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "No answer"))
                .when(razorpay).createOrder(anyString(), any(), anyString());
        org.mockito.Mockito.doThrow(new com.qualityos.common.exception.QualityOsException(
                        com.qualityos.common.exception.ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "No answer"))
                .when(razorpay).findOrder(anyString());
        as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE)).andExpect(status().is5xxServerError());

        // Razorpay does not have it, but it is too soon to be sure it is not still being made.
        doReturn(java.util.Optional.empty()).when(razorpay).findOrder(anyString());
        as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message")
                        .value("A payment for this invoice is being started. Try again in a moment."));

        // Later, it is set aside and a new order made.
        asDatabaseSuperuser().update(
                "UPDATE gateway_orders SET created_at = now() - INTERVAL '3 minutes' WHERE invoice_id = ?::uuid", invoice);
        doAnswer(call -> held(call.getArgument(0))).when(razorpay).createOrder(anyString(), any(), anyString());
        as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.gatewayOrderId").isNotEmpty());
        assertThat(orderStatuses()).containsExactly("ABANDONED", "ACTIVE");
    }

    @Test
    void aFailedCreateThatRazorpayNeverSawDoesNotHoldUpTheNextTry() throws Exception {
        org.mockito.Mockito.doThrow(new com.qualityos.common.exception.QualityOsException(
                        com.qualityos.common.exception.ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "No answer"))
                .when(razorpay).createOrder(anyString(), any(), anyString());
        doReturn(java.util.Optional.empty()).when(razorpay).findOrder(anyString());
        as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE)).andExpect(status().is5xxServerError());
        assertThat(orderStatuses()).containsExactly("ABANDONED");

        doAnswer(call -> held(call.getArgument(0))).when(razorpay).createOrder(anyString(), any(), anyString());
        as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE)).andExpect(status().isOk());
        assertThat(orderStatuses()).containsExactly("ABANDONED", "ACTIVE");
    }

    @Test
    void aPaymentWithoutAnAmountIsKeptForAPerson() throws Exception {
        String order = checkout();
        razorpayHolds(order, payment("SUCCESS", null));
        webhook(order, "test-secret").andExpect(status().isOk());
        assertThat(invoiceStatus()).isEqualTo("ISSUED");
        assertThat(orderStatus(order)).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void aPaymentInAnotherCurrencyIsKeptForAPerson() throws Exception {
        String order = checkout();
        razorpayHolds(order, new RazorpayClient.Payment("p-usd", "SUCCESS", total, "USD", "card", "Simulated"));
        webhook(order, "test-secret").andExpect(status().isOk());
        assertThat(invoiceStatus()).isEqualTo("ISSUED");
        assertThat(orderStatus(order)).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void moneyReachingAnAbandonedOrderIsStillApplied() throws Exception {
        String order = checkout();
        asDatabaseSuperuser().update("UPDATE gateway_orders SET status = 'ABANDONED' WHERE gateway_order_id = ?", order);
        razorpayHolds(order, payment("SUCCESS", total));
        webhook(order, "test-secret").andExpect(status().isOk());
        assertThat(invoiceStatus()).isEqualTo("PAID");
        assertThat(orderStatus(order)).isEqualTo("PAID");
    }

    @Test
    void aWrongAmountDoesNotPayTheInvoiceAndIsKeptForAPerson() throws Exception {
        String order = checkout();
        razorpayHolds(order, payment("SUCCESS", BigDecimal.ONE));

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
        razorpayHolds(order, payment("SUCCESS", total));

        as(owner, postJson(BASE + "/gateway-orders/" + order + "/confirm", Map.of()))
                .andExpect(jsonPath("$.data.status").value("NEEDS_REVIEW"));

        invoice = first;
        assertThat(invoiceStatus()).isEqualTo("VOID");
        assertThat(audited("PAYMENT_NEEDS_REVIEW")).isEqualTo(1);
    }

    @Test
    void aPaidInvoiceCannotBeCheckedOutAgain() throws Exception {
        String order = checkout();
        razorpayHolds(order, payment("SUCCESS", total));
        webhook(order, "test-secret").andExpect(status().isOk());

        as(owner, postJson(BASE + "/invoices/" + invoice + "/checkout", PHONE)).andExpect(status().isConflict());
    }

    /** Razorpay's refund.processed event: our refund id is its receipt, and our order is in its notes. */
    private ResultActions refundWebhook(String order, String refundId) throws Exception {
        return signed(("{\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":{\"id\":\"rfnd_1\","
                + "\"receipt\":\"" + refundId + "\",\"notes\":{\"order\":\"" + order + "\"}}}}}")
                .getBytes(StandardCharsets.UTF_8), "test-secret");
    }

    private Session operator() throws Exception {
        Session admin = register();
        makeOperator(admin, "SUPER_ADMIN");
        return admin;
    }

    @Test
    void aCreditNoteOnAGatewayPaymentIsRefundedThroughTheGatewayUntilItSaysItWentThrough() throws Exception {
        String order = checkout();
        razorpayHolds(order, payment("SUCCESS", total));
        webhook(order, "test-secret").andExpect(status().isOk());
        assertThat(invoiceStatus()).isEqualTo("PAID");
        doAnswer(call -> new RazorpayClient.GatewayRefund(call.getArgument(1), "cf-1", "PENDING", null))
                .when(razorpay).refund(eq(order), anyString(), any(), anyString());

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
        verify(razorpay).refund(eq(order), refundId.capture(), refunded.capture(), anyString());
        assertThat(refunded.getValue()).isEqualByComparingTo(total);
        assertThat(refundId.getValue()).matches("[A-Za-z0-9]{3,40}");
        assertThat((String) JsonPath.read(created, "$.data.refund.reference")).isEqualTo(refundId.getValue());

        doReturn(java.util.Optional.of(new RazorpayClient.GatewayRefund(refundId.getValue(), "cf-1", "SUCCESS", null)))
                .when(razorpay).lookupRefund(order, refundId.getValue());
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
        razorpayHolds(order, payment("SUCCESS", null));
        webhook(order, "test-secret").andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("NEEDS_REVIEW");
        doAnswer(call -> new RazorpayClient.GatewayRefund(call.getArgument(1), "cf-2", "SUCCESS", null))
                .when(razorpay).refund(eq(order), anyString(), any(), anyString());

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
        // Razorpay telling us about the payment again never applies it to anything.
        webhook(order, "test-secret").andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("REFUNDED");
        verify(razorpay).refund(eq(order), anyString(), argThat(value -> value.compareTo(total) == 0), anyString());
        as(admin, postJson(refund, Map.of("reason", "Again"))).andExpect(status().isConflict());
        assertThat(audited("PAYMENT_REFUNDED")).isEqualTo(1);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.qualityos.subscription.service.BillingRefunds billingRefunds;

    /** A payment no invoice could take, waiting for review, and the path to refund it. */
    private String unappliedRefundPath(String order) throws Exception {
        razorpayHolds(order, payment("SUCCESS", null));
        webhook(order, "test-secret").andExpect(status().isOk());
        String id = asDatabaseSuperuser().queryForObject(
                "SELECT id::text FROM gateway_orders WHERE gateway_order_id = ?", String.class, order);
        return "/api/v1/platform/organisations/" + owner.tenantId() + "/gateway-orders/" + id + "/refund";
    }

    private List<Map<String, Object>> listed(Session admin, String order) throws Exception {
        String body = as(admin, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/platform/unapplied-payments")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.data[?(@.gatewayOrderId == '" + order + "')]");
    }

    @Test
    void aRefundStillPendingAtRazorpayLeavesThePaymentRefundPendingUntilItGoesThrough() throws Exception {
        String order = checkout();
        String path = unappliedRefundPath(order);
        doAnswer(call -> new RazorpayClient.GatewayRefund(call.getArgument(1), "cf-3", "PENDING", null))
                .when(razorpay).refund(eq(order), anyString(), any(), anyString());
        Session admin = operator();

        as(admin, postJson(path, Map.of("reason", "Paid twice"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        assertThat(orderStatus(order)).isEqualTo("REFUND_PENDING");
        assertThat(listed(admin, order)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("status", "REFUND_PENDING").containsEntry("refundStatus", "PENDING");
        });
        assertThat(audited("PAYMENT_REFUNDED")).isZero();
        // A second refund cannot be asked for while this one is under way.
        as(admin, postJson(path, Map.of("reason", "Again"))).andExpect(status().isConflict());

        String reference = asDatabaseSuperuser().queryForObject(
                "SELECT refund_reference FROM refunds WHERE tenant_id = ?", String.class, owner.tenantId());
        doReturn(java.util.Optional.of(new RazorpayClient.GatewayRefund(reference, "cf-3", "SUCCESS", null)))
                .when(razorpay).lookupRefund(order, reference);
        refundWebhook(order, reference).andExpect(status().isOk());
        assertThat(orderStatus(order)).isEqualTo("REFUNDED");
        assertThat(listed(admin, order)).isEmpty();
        assertThat(audited("PAYMENT_REFUNDED")).isEqualTo(1);
    }

    @Test
    void aFailedRefundPutsThePaymentBackForReviewWithTheReason() throws Exception {
        String order = checkout();
        String path = unappliedRefundPath(order);
        doAnswer(call -> new RazorpayClient.GatewayRefund(call.getArgument(1), null, "FAILED",
                "Razorpay refused the refund: insufficient balance"))
                .when(razorpay).refund(eq(order), anyString(), any(), anyString());
        Session admin = operator();

        as(admin, postJson(path, Map.of("reason", "Paid twice"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));
        assertThat(orderStatus(order)).isEqualTo("NEEDS_REVIEW");
        assertThat(listed(admin, order)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("status", "NEEDS_REVIEW").containsEntry("refundStatus", "FAILED");
            assertThat((String) row.get("refundFailure")).contains("insufficient balance");
        });
        assertThat(audited("PAYMENT_REFUND_FAILED")).isEqualTo(1);

        // Refunded again, under a new reference, once the first is known to have failed.
        doAnswer(call -> new RazorpayClient.GatewayRefund(call.getArgument(1), "cf-4", "SUCCESS", null))
                .when(razorpay).refund(eq(order), anyString(), any(), anyString());
        as(admin, postJson(path, Map.of("reason", "Paid twice"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        assertThat(orderStatus(order)).isEqualTo("REFUNDED");
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(DISTINCT refund_reference) FROM refunds WHERE tenant_id = ?", Long.class,
                owner.tenantId())).isEqualTo(2);
    }

    @Test
    void aRefundWhoseAnswerNeverCameIsSentAgainUnderTheSameReferenceOnlyIfRazorpayNeverGotIt() throws Exception {
        String order = checkout();
        String path = unappliedRefundPath(order);
        doAnswer(call -> {
            throw new com.qualityos.common.exception.QualityOsException(
                    com.qualityos.common.exception.ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "No answer");
        }).when(razorpay).refund(eq(order), anyString(), any(), anyString());
        Session admin = operator();

        // The refund is kept, waiting, though Razorpay never answered.
        as(admin, postJson(path, Map.of("reason", "Paid twice"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        assertThat(orderStatus(order)).isEqualTo("REFUND_PENDING");
        String refundId = asDatabaseSuperuser().queryForObject(
                "SELECT id::text FROM refunds WHERE tenant_id = ?", String.class, owner.tenantId());
        String reference = asDatabaseSuperuser().queryForObject(
                "SELECT refund_reference FROM refunds WHERE tenant_id = ?", String.class, owner.tenantId());
        asDatabaseSuperuser().update("UPDATE refunds SET created_at = now() - INTERVAL '5 minutes' WHERE id = ?::uuid",
                refundId);

        // The job finds it waiting. Razorpay never received it, so it is sent again with the same reference.
        UUID tenant = owner.tenantId();
        assertThat(com.qualityos.common.tenant.TenantContext.callAs(tenant, null,
                () -> billingRefunds.waiting(java.time.Instant.now().minusSeconds(120))))
                .containsExactly(UUID.fromString(refundId));
        doReturn(java.util.Optional.empty()).when(razorpay).lookupRefund(order, reference);
        doAnswer(call -> new RazorpayClient.GatewayRefund(call.getArgument(1), "cf-5", "SUCCESS", null))
                .when(razorpay).refund(eq(order), anyString(), any(), anyString());
        com.qualityos.common.tenant.TenantContext.callAs(tenant, null,
                () -> billingRefunds.reconcile(UUID.fromString(refundId)));

        verify(razorpay, times(2)).refund(eq(order), eq(reference), any(), anyString());
        assertThat(orderStatus(order)).isEqualTo("REFUNDED");
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM refunds WHERE tenant_id = ?", Long.class, owner.tenantId())).isEqualTo(1);
    }

    @Test
    void aRefundRazorpayTookThoughItsAnswerWasLostIsNeverSentAgain() throws Exception {
        String order = checkout();
        String path = unappliedRefundPath(order);
        doAnswer(call -> {
            throw new com.qualityos.common.exception.QualityOsException(
                    com.qualityos.common.exception.ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "Timed out");
        }).when(razorpay).refund(eq(order), anyString(), any(), anyString());
        Session admin = operator();
        as(admin, postJson(path, Map.of("reason", "Paid twice"))).andExpect(status().isOk());
        String refundId = asDatabaseSuperuser().queryForObject(
                "SELECT id::text FROM refunds WHERE tenant_id = ?", String.class, owner.tenantId());
        String reference = asDatabaseSuperuser().queryForObject(
                "SELECT refund_reference FROM refunds WHERE tenant_id = ?", String.class, owner.tenantId());

        doReturn(java.util.Optional.of(new RazorpayClient.GatewayRefund(reference, "cf-6", "SUCCESS", null)))
                .when(razorpay).lookupRefund(order, reference);
        as(admin, postJson("/api/v1/platform/organisations/" + owner.tenantId() + "/refunds/" + refundId + "/refresh",
                        Map.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
        verify(razorpay, times(1)).refund(eq(order), anyString(), any(), anyString());
        assertThat(orderStatus(order)).isEqualTo("REFUNDED");
    }

    private static com.qualityos.common.exception.QualityOsException noAnswer() {
        return new com.qualityos.common.exception.QualityOsException(
                com.qualityos.common.exception.ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "No answer");
    }

    private String onlyRefund(String column) {
        return asDatabaseSuperuser().queryForObject(
                "SELECT " + column + "::text FROM refunds WHERE tenant_id = ?", String.class, owner.tenantId());
    }

    /** Runs the same work on two threads at once, under this organisation, and waits for both. */
    private void twiceAtOnce(java.util.function.Supplier<?> work) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<?>> running = List.of(
                    pool.submit(() -> com.qualityos.common.tenant.TenantContext.callAs(owner.tenantId(), null, work)),
                    pool.submit(() -> com.qualityos.common.tenant.TenantContext.callAs(owner.tenantId(), null, work)));
            for (java.util.concurrent.Future<?> each : running) {
                each.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aWebhookArrivingWhileTheRefundIsStillBeingSentIsAppliedOnce() throws Exception {
        String order = checkout();
        String path = unappliedRefundPath(order);
        doAnswer(call -> java.util.Optional.of(
                new RazorpayClient.GatewayRefund(call.getArgument(1), "cf-7", "SUCCESS", null)))
                .when(razorpay).lookupRefund(eq(order), anyString());
        // Razorpay takes the refund and calls the webhook before its answer to the request comes back.
        doAnswer(call -> {
            String reference = call.getArgument(1);
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    refundWebhook(order, reference).andExpect(status().isOk());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(orderStatus(order)).isEqualTo("REFUNDED");
            return new RazorpayClient.GatewayRefund(reference, "cf-7", "SUCCESS", null);
        }).when(razorpay).refund(eq(order), anyString(), any(), anyString());

        as(operator(), postJson(path, Map.of("reason", "Paid twice"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        verify(razorpay, times(1)).refund(eq(order), anyString(), any(), anyString());
        assertThat(orderStatus(order)).isEqualTo("REFUNDED");
        assertThat(audited("REFUND_SUCCEEDED")).isEqualTo(1);
        assertThat(audited("PAYMENT_REFUNDED")).isEqualTo(1);
    }

    @Test
    void twoChecksOfTheSameRefundAtOnceApplyTheAnswerOnce() throws Exception {
        String order = checkout();
        String path = unappliedRefundPath(order);
        doAnswer(call -> {
            throw noAnswer();
        }).when(razorpay).refund(eq(order), anyString(), any(), anyString());
        as(operator(), postJson(path, Map.of("reason", "Paid twice"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        UUID refundId = UUID.fromString(onlyRefund("id"));

        // Both checks read the refund as waiting before either records Razorpay's answer.
        java.util.concurrent.CyclicBarrier bothAsked = new java.util.concurrent.CyclicBarrier(2);
        doAnswer(call -> {
            bothAsked.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return java.util.Optional.of(new RazorpayClient.GatewayRefund(call.getArgument(1), "cf-8", "SUCCESS", null));
        }).when(razorpay).lookupRefund(eq(order), anyString());

        twiceAtOnce(() -> billingRefunds.reconcile(refundId));

        verify(razorpay, times(1)).refund(eq(order), anyString(), any(), anyString());
        assertThat(onlyRefund("status")).isEqualTo("SUCCEEDED");
        assertThat(orderStatus(order)).isEqualTo("REFUNDED");
        assertThat(audited("REFUND_SUCCEEDED")).isEqualTo(1);
        assertThat(audited("PAYMENT_REFUNDED")).isEqualTo(1);
    }

    @Test
    void twoChecksThatBothFindNothingSendItAgainOnlyUnderItsOwnReference() throws Exception {
        String order = checkout();
        String path = unappliedRefundPath(order);
        doAnswer(call -> {
            throw noAnswer();
        }).when(razorpay).refund(eq(order), anyString(), any(), anyString());
        as(operator(), postJson(path, Map.of("reason", "Paid twice"))).andExpect(status().isOk());
        UUID refundId = UUID.fromString(onlyRefund("id"));
        String reference = onlyRefund("refund_reference");

        java.util.concurrent.CyclicBarrier bothAsked = new java.util.concurrent.CyclicBarrier(2);
        doAnswer(call -> {
            bothAsked.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return java.util.Optional.empty();
        }).when(razorpay).lookupRefund(eq(order), anyString());
        doAnswer(call -> new RazorpayClient.GatewayRefund(call.getArgument(1), "cf-9", "SUCCESS", null))
                .when(razorpay).refund(eq(order), anyString(), any(), anyString());

        twiceAtOnce(() -> billingRefunds.reconcile(refundId));

        // Razorpay refunds a reference once, however often it is sent, so every send must carry the same one.
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(razorpay, org.mockito.Mockito.atLeast(2)).refund(eq(order), sent.capture(), any(), anyString());
        assertThat(sent.getAllValues()).containsOnly(reference);
        assertThat(asDatabaseSuperuser().queryForObject(
                "SELECT count(*) FROM refunds WHERE tenant_id = ?", Long.class, owner.tenantId())).isEqualTo(1);
        assertThat(audited("REFUND_SUCCEEDED")).isEqualTo(1);
        assertThat(audited("PAYMENT_REFUNDED")).isEqualTo(1);
    }

    @Test
    void aCreditNoteRefundIsSentAtOnceShowsWaitingIfRazorpayIsSilentAndIsFinishedByTheJob() throws Exception {
        String order = checkout();
        razorpayHolds(order, payment("SUCCESS", total));
        webhook(order, "test-secret").andExpect(status().isOk());
        doAnswer(call -> {
            throw noAnswer();
        }).when(razorpay).refund(eq(order), anyString(), any(), anyString());
        BigDecimal amount = asDatabaseSuperuser().queryForObject(
                "SELECT amount FROM subscription_invoices WHERE id = ?::uuid", BigDecimal.class, invoice);

        // The credit note stands even though Razorpay did not answer: the refund was asked for in the same request.
        as(operator(), postJson("/api/v1/platform/organisations/" + owner.tenantId() + "/invoices/" + invoice
                        + "/credit-notes", Map.of("amount", amount.toPlainString(), "reason", "Cancelled",
                        "refundBy", "GATEWAY", "endAccess", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.refund.status").value("PENDING"));
        verify(razorpay, times(1)).refund(eq(order), anyString(), any(), anyString());
        // The customer sees it as waiting, with no date yet.
        as(owner, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BASE + "/credit-notes"))
                .andExpect(jsonPath("$.data[0].refund.status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].refund.refundedOn").doesNotExist());

        // The job leaves it alone while it may still be on its way, so it is never sent twice side by side.
        UUID refundId = UUID.fromString(onlyRefund("id"));
        java.time.Instant settle = java.time.Instant.now().minus(java.time.Duration.ofMinutes(2));
        assertThat(com.qualityos.common.tenant.TenantContext.callAs(owner.tenantId(), null,
                () -> billingRefunds.waiting(settle))).isEmpty();

        // Two minutes on, the job picks it up, finds Razorpay never got it, and sends it under the same reference.
        asDatabaseSuperuser().update("UPDATE refunds SET created_at = now() - INTERVAL '3 minutes' WHERE id = ?",
                refundId);
        assertThat(com.qualityos.common.tenant.TenantContext.callAs(owner.tenantId(), null,
                () -> billingRefunds.waiting(settle))).containsExactly(refundId);
        doReturn(java.util.Optional.empty()).when(razorpay).lookupRefund(eq(order), anyString());
        doAnswer(call -> new RazorpayClient.GatewayRefund(call.getArgument(1), "cf-10", "SUCCESS", null))
                .when(razorpay).refund(eq(order), anyString(), any(), anyString());
        com.qualityos.common.tenant.TenantContext.callAs(owner.tenantId(), null,
                () -> billingRefunds.reconcile(refundId));

        verify(razorpay, times(2)).refund(eq(order), eq(onlyRefund("refund_reference")), any(), anyString());
        as(owner, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BASE + "/credit-notes"))
                .andExpect(jsonPath("$.data[0].refund.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].refund.refundedOn").isNotEmpty());
    }
}
