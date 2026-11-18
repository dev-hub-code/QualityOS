package com.qualityos.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.exception.QualityOsException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Speaks real HTTP to a local server, so the headers and body are checked as they would leave the application. */
class RazorpayClientTest {

    /** One request as it arrived. */
    private record Seen(String method, String path, String query, Map<String, List<String>> headers, String body) {

        String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> entry.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Seen> seen = java.util.Collections.synchronizedList(new ArrayList<>());
    /** Answers, one per request, in order: status and body. */
    private final ConcurrentLinkedQueue<Object[]> answers = new ConcurrentLinkedQueue<>();
    private HttpServer server;
    private RazorpayClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestURI().getQuery(), Map.copyOf(exchange.getRequestHeaders()),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Object[] next = answers.poll();
            byte[] body = (next == null ? "{}" : (String) next[1]).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(next == null ? 200 : (int) next[0], body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new RazorpayClient(properties("whsec"), json);
    }

    private RazorpayProperties properties(String webhookSecret) {
        return new RazorpayProperties("rzp_test_key", "key-secret", webhookSecret,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(int status, String body) {
        answers.add(new Object[] {status, body});
    }

    private static final String ORDER = """
            {"id":"order_1","entity":"order","amount":707882,"currency":"INR","receipt":"QOS-1","status":"paid"}""";
    private static final String FOUND = "{\"entity\":\"collection\",\"count\":1,\"items\":[" + ORDER + "]}";
    private static final String CAPTURED = """
            {"entity":"collection","count":2,"items":[
             {"id":"pay_0","amount":707882,"currency":"INR","status":"failed","method":"card",
              "error_description":"Card declined"},
             {"id":"pay_1","amount":707882,"currency":"INR","status":"captured","method":"upi","captured":true}]}""";

    @Test
    void createsAnOrderInPaiseUnderOurReference() {
        answer(200, ORDER);

        RazorpayClient.Order order = client.createOrder("QOS-1", new BigDecimal("7078.82"), "Invoice INV-1");

        assertThat(order.id()).isEqualTo("order_1");
        assertThat(order.amount()).isEqualByComparingTo("7078.82");
        Seen request = seen.getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/orders");
        assertThat(request.header("Authorization")).isEqualTo("Basic "
                + Base64.getEncoder().encodeToString("rzp_test_key:key-secret".getBytes(StandardCharsets.UTF_8)));
        JsonNode body = json.readTree(request.body());
        assertThat(body.get("amount").asLong()).isEqualTo(707882);
        assertThat(body.get("currency").asString()).isEqualTo("INR");
        assertThat(body.get("receipt").asString()).isEqualTo("QOS-1");
        assertThat(body.path("notes").get("note").asString()).isEqualTo("Invoice INV-1");
    }

    @Test
    void anOrderMadeAgainUnderTheSameReferenceIsTheFirstOne() {
        answer(400, "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"Duplicate receipt\"}}");
        answer(200, FOUND);

        assertThat(client.createOrder("QOS-1", new BigDecimal("7078.82"), "Invoice INV-1").id()).isEqualTo("order_1");
        assertThat(seen.get(1).path()).isEqualTo("/v1/orders");
        assertThat(seen.get(1).query()).isEqualTo("receipt=QOS-1");
    }

    @Test
    void anOrderRefusedForAnotherReasonSaysSoWithoutLeakingTheResponse() {
        answer(400, "{\"error\":{\"description\":\"The amount must be at least INR 1.00\"}}");
        answer(200, "{\"entity\":\"collection\",\"count\":0,\"items\":[]}");

        assertThatThrownBy(() -> client.createOrder("QOS-1", new BigDecimal("0.50"), "Invoice INV-1"))
                .isInstanceOf(QualityOsException.class)
                .hasMessageContaining("at least INR 1.00");
    }

    @Test
    void readsAnOrdersPaymentsInRupees() {
        answer(200, FOUND);
        answer(200, CAPTURED);

        List<RazorpayClient.Payment> payments = client.payments("QOS-1");

        assertThat(seen.get(1).path()).isEqualTo("/v1/orders/order_1/payments");
        assertThat(payments).extracting(RazorpayClient.Payment::status).containsExactly("FAILED", "SUCCESS");
        assertThat(payments.get(0).message()).isEqualTo("Card declined");
        assertThat(payments.get(1).amount()).isEqualByComparingTo("7078.82");
        assertThat(payments.get(1).method()).isEqualTo("upi");
    }

    @Test
    void anOrderRazorpayDoesNotHaveHasNoPaymentsRatherThanAnError() {
        answer(200, "{\"entity\":\"collection\",\"count\":0,\"items\":[]}");

        assertThat(client.findOrder("QOS-9")).isEmpty();
        answer(200, "{\"entity\":\"collection\",\"count\":0,\"items\":[]}");
        assertThat(client.payments("QOS-9")).isEmpty();
    }

    @Test
    void noAnswerIsAPlainErrorThatQuotesNothing() {
        server.stop(0);

        assertThatThrownBy(() -> client.findOrder("QOS-1"))
                .isInstanceOf(QualityOsException.class)
                .satisfies(e -> assertThat(((QualityOsException) e).errorCode())
                        .isEqualTo(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE));
    }

    @Test
    void capturesAnAuthorisedPaymentForItsAmount() {
        answer(200, "{\"id\":\"pay_1\",\"status\":\"captured\"}");

        assertThat(client.capture("pay_1", new BigDecimal("7078.82"))).isTrue();
        assertThat(seen.getFirst().path()).isEqualTo("/v1/payments/pay_1/capture");
        JsonNode body = json.readTree(seen.getFirst().body());
        assertThat(body.get("amount").asLong()).isEqualTo(707882);
        assertThat(body.get("currency").asString()).isEqualTo("INR");

        answer(400, "{\"error\":{\"description\":\"This payment has already been captured\"}}");
        assertThat(client.capture("pay_1", new BigDecimal("7078.82"))).isFalse();
    }

    @Test
    void refundsTheCapturedPaymentUnderOurIdAndReadsWhereItStands() {
        answer(200, FOUND);
        answer(200, CAPTURED);
        answer(200, """
                {"id":"rfnd_1","entity":"refund","amount":707882,"payment_id":"pay_1","receipt":"RF1234567890",
                 "status":"pending"}""");

        RazorpayClient.GatewayRefund refund = client.refund("QOS-1", "RF1234567890", new BigDecimal("7078.82"),
                "Credit note CN-1");

        assertThat(refund).isEqualTo(new RazorpayClient.GatewayRefund("RF1234567890", "rfnd_1", "PENDING", null));
        Seen request = seen.get(2);
        assertThat(request.path()).isEqualTo("/v1/payments/pay_1/refund");
        assertThat(request.header("X-Refund-Idempotency")).isEqualTo("RF1234567890");
        JsonNode body = json.readTree(request.body());
        assertThat(body.get("amount").asLong()).isEqualTo(707882);
        assertThat(body.get("receipt").asString()).isEqualTo("RF1234567890");
        // The webhook finds the organisation by the order named here.
        assertThat(body.path("notes").get("order").asString()).isEqualTo("QOS-1");
    }

    @Test
    void aRefundSentAgainWithTheSameIdAnswersWithTheFirstOne() {
        answer(200, FOUND);
        answer(200, CAPTURED);
        answer(400, "{\"error\":{\"description\":\"Duplicate receipt found for this refund request\"}}");
        answer(200, FOUND);
        answer(200, CAPTURED);
        answer(200, """
                {"entity":"collection","count":2,"items":[
                 {"id":"rfnd_0","receipt":"RF-other-0001","status":"processed"},
                 {"id":"rfnd_1","receipt":"RF1234567890","status":"processed"}]}""");

        assertThat(client.refund("QOS-1", "RF1234567890", new BigDecimal("10.00"), "Again"))
                .isEqualTo(new RazorpayClient.GatewayRefund("RF1234567890", "rfnd_1", "SUCCESS", null));
        assertThat(seen.get(5).path()).isEqualTo("/v1/payments/pay_1/refunds");
    }

    @Test
    void aRefundRazorpayRefusesFailsWithItsReason() {
        answer(200, FOUND);
        answer(200, CAPTURED);
        answer(400, "{\"error\":{\"description\":\"The refund amount exceeds the amount left\"}}");

        RazorpayClient.GatewayRefund refund = client.refund("QOS-1", "RF1234567890", new BigDecimal("99999"), "Too much");

        assertThat(refund.status()).isEqualTo("FAILED");
        assertThat(refund.message()).isEqualTo("Razorpay refused the refund: The refund amount exceeds the amount left");
    }

    @Test
    void aRefundStillBeingHandledIsLeftWaitingNotFailed() {
        answer(200, FOUND);
        answer(200, CAPTURED);
        answer(409, "{\"error\":{\"description\":\"Request in progress\"}}");

        assertThatThrownBy(() -> client.refund("QOS-1", "RF1234567890", new BigDecimal("10.00"), "Again"))
                .isInstanceOf(QualityOsException.class);
    }

    @Test
    void aRefundRazorpayNeverReceivedIsNotFoundRatherThanAnError() {
        answer(200, FOUND);
        answer(200, CAPTURED);
        answer(200, "{\"entity\":\"collection\",\"count\":0,\"items\":[]}");

        assertThat(client.lookupRefund("QOS-1", "RF1234567890")).isEmpty();
    }

    @Test
    void anOrderWithNoCapturedPaymentCannotBeRefunded() {
        answer(200, FOUND);
        answer(200, "{\"entity\":\"collection\",\"count\":0,\"items\":[]}");

        assertThat(client.refund("QOS-1", "RF1234567890", new BigDecimal("10.00"), "None").status())
                .isEqualTo("FAILED");
        assertThat(seen).hasSize(2);
    }

    @Test
    void acceptsOnlyTheSignatureOfTheExactBytesReceived() throws Exception {
        byte[] body = "{\"payload\":{\"amount\":170.00}}".getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("whsec".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(body));

        assertThat(client.signatureMatches(body, signature)).isTrue();
        assertThat(client.signatureMatches("{\"payload\":{\"amount\":170}}".getBytes(StandardCharsets.UTF_8),
                signature)).isFalse();
        assertThat(client.signatureMatches(body, null)).isFalse();
        // Without a webhook secret, no webhook is believed.
        assertThat(new RazorpayClient(properties(""), json).signatureMatches(body, signature)).isFalse();
    }

    @Test
    void theModeFollowsTheKeyAndTheSecretStaysOutOfLogs() {
        assertThat(properties("whsec").mode()).isEqualTo("sandbox");
        assertThat(new RazorpayProperties("rzp_live_key", "s", "w", null).mode()).isEqualTo("production");
        assertThat(new RazorpayProperties("rzp_live_key", "s", "w", null).apiUrl())
                .isEqualTo("https://api.razorpay.com/v1");
        assertThat(properties("whsec").toString()).doesNotContain("key-secret").doesNotContain("whsec");
    }
}
