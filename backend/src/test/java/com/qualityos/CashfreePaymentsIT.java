package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
        return new CashfreeClient.Payment(UUID.randomUUID().toString(), status, amount, "upi", "Simulated");
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

        doReturn(new CashfreeClient.Order(order, "ACTIVE", "session_2")).when(cashfree).order(order);
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
}
