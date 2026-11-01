package com.qualityos.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.exception.QualityOsException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Speaks real HTTP to a local server, so the headers and body are checked as they would leave the application. */
class CashfreeClientTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<HttpExchange> seen = new AtomicReference<>();
    private final AtomicReference<String> sent = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> reply = new AtomicReference<>("{}");
    private HttpServer server;
    private CashfreeClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.set(exchange);
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new CashfreeClient(new CashfreeProperties("app-id", "test-secret", "sandbox",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/pg"), json);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void createsAnOrderTheWayCashfreeDocumentsIt() {
        reply.set("""
                {"order_id":"QOS-1","order_status":"ACTIVE","payment_session_id":"session_1","cf_order_id":99}""");

        CashfreeClient.Order order = client.createOrder(new CashfreeClient.NewOrder(
                "QOS-1", new BigDecimal("7078.82"),
                new CashfreeClient.Customer("tenant", "Asha", "asha@example.com", "9876543210"),
                "https://app.example.com/settings/billing?order_id=QOS-1",
                OffsetDateTime.parse("2026-11-01T12:00:00+05:30"), "Invoice INV-1"));

        assertThat(order.paymentSessionId()).isEqualTo("session_1");
        assertThat(seen.get().getRequestMethod()).isEqualTo("POST");
        assertThat(seen.get().getRequestURI().getPath()).isEqualTo("/pg/orders");
        assertThat(seen.get().getRequestHeaders().getFirst("x-api-version")).isEqualTo("2026-01-01");
        assertThat(seen.get().getRequestHeaders().getFirst("x-client-id")).isEqualTo("app-id");
        assertThat(seen.get().getRequestHeaders().getFirst("x-client-secret")).isEqualTo("test-secret");
        JsonNode body = json.readTree(sent.get());
        assertThat(body.get("order_amount").decimalValue()).isEqualByComparingTo("7078.82");
        assertThat(body.get("order_currency").asString()).isEqualTo("INR");
        assertThat(body.get("order_expiry_time").asString()).isEqualTo("2026-11-01T12:00+05:30");
        assertThat(body.at("/customer_details/customer_phone").asString()).isEqualTo("9876543210");
        assertThat(body.at("/order_meta/return_url").asString()).endsWith("order_id=QOS-1");
    }

    @Test
    void readsPaymentsWhetherTheIdIsATextOrANumber() {
        reply.set("""
                [{"cf_payment_id":"1453995084705707520","payment_status":"SUCCESS","payment_amount":170.00,
                  "payment_group":"upi","payment_message":"ok"},
                 {"cf_payment_id":1453002795,"payment_status":"FAILED","payment_amount":170.00,"payment_group":"card"}]
                """);

        List<CashfreeClient.Payment> payments = client.payments("QOS-1");

        assertThat(seen.get().getRequestURI().getPath()).isEqualTo("/pg/orders/QOS-1/payments");
        assertThat(payments).extracting(CashfreeClient.Payment::id).containsExactly("1453995084705707520", "1453002795");
        assertThat(payments.getFirst().amount()).isEqualByComparingTo("170");
        assertThat(payments.get(1).message()).isNull();
    }

    @Test
    void aRefusalDoesNotLeakWhatCashfreeSaid() {
        status.set(401);
        reply.set("{\"message\":\"authentication Failed for app-id\"}");

        assertThatThrownBy(() -> client.order("QOS-1"))
                .isInstanceOfSatisfying(QualityOsException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE))
                .hasMessageNotContaining("app-id");
    }

    /** The expected value was worked out with Python's hmac module, not with this code. */
    @Test
    void acceptsOnlyTheSignatureOfTheExactBytesReceived() {
        String timestamp = "1750000000000";
        String body = "{\"type\":\"PAYMENT_SUCCESS_WEBHOOK\",\"amount\":170.00}";
        String signature = "1b3Wuja7mIT1M7OUgdVpOQdv+ndNhgx/36yzjGaZkhc=";

        assertThat(client.signatureMatches(body.getBytes(StandardCharsets.UTF_8), timestamp, signature)).isTrue();
        assertThat(client.signatureMatches(body.replace("170.00", "170").getBytes(StandardCharsets.UTF_8), timestamp, signature)).isFalse();
        assertThat(client.signatureMatches(body.getBytes(StandardCharsets.UTF_8), "1750000000001", signature)).isFalse();
        assertThat(client.signatureMatches(body.getBytes(StandardCharsets.UTF_8), null, signature)).isFalse();
        assertThat(client.signatureMatches(body.getBytes(StandardCharsets.UTF_8), timestamp, null)).isFalse();
    }

    @Test
    void theSecretStaysOutOfLogs() {
        assertThat(new CashfreeProperties("app-id", "test-secret", "sandbox", null).toString())
                .doesNotContain("test-secret").doesNotContain("app-id");
    }

    @Test
    void theEnvironmentPicksTheAddress() {
        assertThat(new CashfreeProperties("a", "b", "sandbox", "").apiUrl()).isEqualTo("https://sandbox.cashfree.com/pg");
        assertThat(new CashfreeProperties("a", "b", "production", "").apiUrl()).isEqualTo("https://api.cashfree.com/pg");
        assertThat(new CashfreeProperties("", "b", "sandbox", "").configured()).isFalse();
    }
}
