package com.qualityos.subscription.service;

import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.exception.QualityOsException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cashfree's Payment Gateway API. Nothing here decides anything about an invoice. */
@Slf4j
@Component
@EnableConfigurationProperties(CashfreeProperties.class)
public class CashfreeClient {

    private static final String API_VERSION = "2026-01-01";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    public record Customer(String id, String name, String email, String phone) {

        @Override
        public String toString() {
            return "Customer[" + id + "]";
        }
    }

    public record NewOrder(
            String orderId, BigDecimal amount, Customer customer, String returnUrl, OffsetDateTime expiresAt, String note) {
    }

    public record Order(String orderId, String status, String paymentSessionId) {

        @Override
        public String toString() {
            return "Order[" + orderId + ", " + status + "]";
        }
    }

    /** @param amount and currency as Cashfree reports them, either null when it leaves them out */
    public record Payment(String id, String status, BigDecimal amount, String currency, String group, String message) {
    }

    private final CashfreeProperties properties;
    private final JsonMapper json;
    private final RestClient rest;

    CashfreeClient(CashfreeProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        this.rest = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(properties.apiUrl())
                .defaultHeader("x-api-version", API_VERSION)
                .defaultHeader("x-client-id", String.valueOf(properties.clientId()))
                .defaultHeader("x-client-secret", String.valueOf(properties.clientSecret()))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public Order createOrder(NewOrder order) {
        Map<String, Object> body = Map.of(
                "order_id", order.orderId(),
                "order_amount", order.amount(),
                "order_currency", "INR",
                "order_note", order.note(),
                "order_expiry_time", order.expiresAt().toString(),
                "customer_details", Map.of(
                        "customer_id", order.customer().id(),
                        "customer_name", order.customer().name(),
                        "customer_email", order.customer().email(),
                        "customer_phone", order.customer().phone()),
                "order_meta", Map.of("return_url", order.returnUrl()));
        return parseOrder(call(() -> rest.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body)).retrieve().body(String.class)));
    }

    /** @return empty when Cashfree has no order with this id */
    public Optional<Order> findOrder(String orderId) {
        try {
            return Optional.of(parseOrder(call(() -> {
                try {
                    return rest.get().uri("/orders/{id}", orderId).retrieve().body(String.class);
                } catch (HttpClientErrorException.NotFound e) {
                    throw new OrderNotFound();
                }
            })));
        } catch (OrderNotFound e) {
            return Optional.empty();
        }
    }

    private static final class OrderNotFound extends RuntimeException {
        OrderNotFound() {
            super(null, null, false, false);
        }
    }

    public List<Payment> payments(String orderId) {
        JsonNode rows = json.readTree(
                call(() -> rest.get().uri("/orders/{id}/payments", orderId).retrieve().body(String.class)));
        List<Payment> payments = new ArrayList<>();
        for (JsonNode row : rows) {
            payments.add(new Payment(
                    text(row, "cf_payment_id"), text(row, "payment_status"),
                    row.hasNonNull("payment_amount") ? new BigDecimal(text(row, "payment_amount")) : null,
                    text(row, "payment_currency"), text(row, "payment_group"), text(row, "payment_message")));
        }
        return payments;
    }

    /** @param status PENDING, SUCCESS, CANCELLED or ONHOLD, as Cashfree reports it */
    public record GatewayRefund(String refundId, String gatewayRefundId, String status, String message) {
    }

    /**
     * Asks Cashfree to return {@code amount} of what the order took. {@code refundId} is ours, and makes a retry safe:
     * sent again, Cashfree refuses a second refund with it, and this answers with the first one.
     *
     * <p>A refund Cashfree refuses outright, such as for more than is left of the order, comes back as {@code FAILED}
     * with Cashfree's reason. No answer at all throws, and the refund stays as it was, to be looked up later.
     */
    public GatewayRefund refund(String orderId, String refundId, BigDecimal amount, String note) {
        Map<String, Object> body = Map.of(
                "refund_amount", amount,
                "refund_id", refundId,
                "refund_note", note.length() > 100 ? note.substring(0, 100) : note);
        try {
            return parseRefund(call(() -> {
                try {
                    return rest.post().uri("/orders/{id}/refunds", orderId)
                            .contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body)).retrieve()
                            .body(String.class);
                } catch (HttpClientErrorException.Conflict e) {
                    throw new RefundExists();
                } catch (HttpClientErrorException e) {
                    throw new RefundRefused(reason(e));
                }
            }));
        } catch (RefundExists e) {
            return findRefund(orderId, refundId);
        } catch (RefundRefused e) {
            return new GatewayRefund(refundId, null, "FAILED", e.getMessage());
        }
    }

    public GatewayRefund findRefund(String orderId, String refundId) {
        return parseRefund(call(() -> rest.get().uri("/orders/{id}/refunds/{refund}", orderId, refundId).retrieve()
                .body(String.class)));
    }

    /** Empty when Cashfree never received the refund, so it is safe to send with the same id. */
    public Optional<GatewayRefund> lookupRefund(String orderId, String refundId) {
        try {
            return Optional.of(parseRefund(call(() -> {
                try {
                    return rest.get().uri("/orders/{id}/refunds/{refund}", orderId, refundId).retrieve()
                            .body(String.class);
                } catch (HttpClientErrorException.NotFound e) {
                    throw new RefundNotFound();
                }
            })));
        } catch (RefundNotFound e) {
            return Optional.empty();
        }
    }

    /** Cashfree's own words for a refusal, which name no card or account. */
    private String reason(HttpClientErrorException e) {
        try {
            String message = text(json.readTree(e.getResponseBodyAsString()), "message");
            return message == null ? "Cashfree refused the refund." : "Cashfree refused the refund: " + message;
        } catch (RuntimeException unreadable) {
            return "Cashfree refused the refund.";
        }
    }

    private static final class RefundExists extends RuntimeException {
        RefundExists() {
            super(null, null, false, false);
        }
    }

    private static final class RefundNotFound extends RuntimeException {
        RefundNotFound() {
            super(null, null, false, false);
        }
    }

    private static final class RefundRefused extends RuntimeException {
        RefundRefused(String reason) {
            super(reason, null, false, false);
        }
    }

    private GatewayRefund parseRefund(String response) {
        JsonNode refund = json.readTree(response);
        return new GatewayRefund(text(refund, "refund_id"), text(refund, "cf_refund_id"), text(refund, "refund_status"),
                text(refund, "status_description"));
    }

    /**
     * Cashfree signs the timestamp followed directly by the raw body, so the body must be the bytes that arrived.
     * Parsing it first can rewrite an amount such as 170.00 and break the match.
     */
    public boolean signatureMatches(byte[] body, String timestamp, String signature) {
        if (timestamp == null || signature == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.clientSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            byte[] expected = Base64.getEncoder().encode(mac.doFinal(body));
            return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private Order parseOrder(String response) {
        JsonNode order = json.readTree(response);
        return new Order(text(order, "order_id"), text(order, "order_status"), text(order, "payment_session_id"));
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asString() : null;
    }

    /** The cause goes to the log by type only: its message can quote Cashfree's response. */
    private String call(Supplier<String> request) {
        try {
            return request.get();
        } catch (RestClientException e) {
            log.warn("Cashfree request failed: {}", e.getClass().getSimpleName());
            throw new QualityOsException(
                    ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "The payment service did not respond. Please try again.");
        }
    }
}
