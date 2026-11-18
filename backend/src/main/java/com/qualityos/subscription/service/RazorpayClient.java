package com.qualityos.subscription.service;

import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.exception.QualityOsException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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

/**
 * Razorpay's API. Nothing here decides anything about an invoice. Our order reference travels as the Razorpay order's
 * {@code receipt}, which Razorpay keeps unique, so an order can always be found again by it. Amounts go to Razorpay in
 * paise and come back here in rupees.
 */
@Slf4j
@Component
@EnableConfigurationProperties(RazorpayProperties.class)
public class RazorpayClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
    private static final String CURRENCY = "INR";
    private static final BigDecimal PAISE = BigDecimal.valueOf(100);

    /** @param status created, attempted or paid, as Razorpay reports it */
    public record Order(String id, String receipt, String status, BigDecimal amount) {
    }

    /**
     * @param status SUCCESS once captured, FAILED, AUTHORIZED while it waits to be captured, or Razorpay's own word
     * @param amount and currency as Razorpay reports them, either null when it leaves them out
     */
    public record Payment(String id, String status, BigDecimal amount, String currency, String method, String message) {
    }

    /**
     * @param refundId ours, sent to Razorpay as the refund's receipt
     * @param status PENDING, SUCCESS or FAILED
     */
    public record GatewayRefund(String refundId, String gatewayRefundId, String status, String message) {
    }

    private final RazorpayProperties properties;
    private final JsonMapper json;
    private final RestClient rest;

    RazorpayClient(RazorpayProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        this.rest = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(properties.apiUrl())
                .defaultHeaders(headers -> {
                    if (properties.configured()) {
                        headers.setBasicAuth(properties.keyId(), properties.keySecret(), StandardCharsets.UTF_8);
                    }
                })
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Opens an order under our reference. Sent again with the same reference, Razorpay refuses a second order, and this
     * answers with the first.
     */
    public Order createOrder(String reference, BigDecimal amount, String note) {
        Map<String, Object> body = Map.of(
                "amount", paise(amount),
                "currency", CURRENCY,
                "receipt", reference,
                "notes", Map.of("note", note));
        try {
            return parseOrder(json.readTree(call(() -> {
                try {
                    return rest.post().uri("/orders").contentType(MediaType.APPLICATION_JSON)
                            .body(json.writeValueAsString(body)).retrieve().body(String.class);
                } catch (HttpClientErrorException.BadRequest e) {
                    throw new Refused(reason(e, "order"));
                }
            })));
        } catch (Refused e) {
            // A repeat of an order already made is refused as a duplicate receipt; anything else is a real refusal.
            return findOrder(reference).orElseThrow(() -> new QualityOsException(
                    ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, e.getMessage()));
        }
    }

    /** @return empty when Razorpay has no order under this reference */
    public Optional<Order> findOrder(String reference) {
        JsonNode items = json.readTree(call(() -> rest.get()
                .uri(uri -> uri.path("/orders").queryParam("receipt", reference).build())
                .retrieve().body(String.class))).path("items");
        for (JsonNode order : items) {
            if (reference.equals(text(order, "receipt"))) {
                return Optional.of(parseOrder(order));
            }
        }
        return Optional.empty();
    }

    /** By Razorpay's own id, as a webhook names it. */
    public Order order(String razorpayOrderId) {
        return parseOrder(json.readTree(call(() -> rest.get().uri("/orders/{id}", razorpayOrderId).retrieve()
                .body(String.class))));
    }

    /** Every payment tried on our order, or none if Razorpay has no such order. */
    public List<Payment> payments(String reference) {
        Optional<Order> order = findOrder(reference);
        if (order.isEmpty()) {
            return List.of();
        }
        JsonNode items = json.readTree(call(() -> rest.get().uri("/orders/{id}/payments", order.get().id())
                .retrieve().body(String.class))).path("items");
        List<Payment> payments = new ArrayList<>();
        for (JsonNode row : items) {
            payments.add(new Payment(
                    text(row, "id"), status(text(row, "status")),
                    row.hasNonNull("amount") ? rupees(row.get("amount").asLong()) : null,
                    text(row, "currency"), text(row, "method"), text(row, "error_description")));
        }
        return payments;
    }

    /**
     * Takes money that was authorised but not yet taken, for the amount authorised. Razorpay returns an uncaptured
     * payment after a few days, so one that is left alone is never kept.
     *
     * @return false when Razorpay would not capture it, such as when it was captured meanwhile
     */
    public boolean capture(String paymentId, BigDecimal amount) {
        Map<String, Object> body = Map.of("amount", paise(amount), "currency", CURRENCY);
        try {
            call(() -> {
                try {
                    return rest.post().uri("/payments/{id}/capture", paymentId).contentType(MediaType.APPLICATION_JSON)
                            .body(json.writeValueAsString(body)).retrieve().body(String.class);
                } catch (HttpClientErrorException e) {
                    throw new Refused(reason(e, "capture"));
                }
            });
            return true;
        } catch (Refused e) {
            log.warn("Razorpay did not capture payment {}: {}", paymentId, e.getMessage());
            return false;
        }
    }

    /**
     * Returns {@code amount} of what our order took. {@code refundId} is ours, sent as the idempotency key and the
     * receipt, so a retry never refunds twice: Razorpay answers a repeat with the first refund.
     *
     * <p>A refund Razorpay refuses outright, such as for more than is left, comes back as {@code FAILED} with Razorpay's
     * reason. No answer at all, or a repeat that arrives while the first is still being handled, throws, and the refund
     * stays as it was, to be looked up later.
     */
    public GatewayRefund refund(String reference, String refundId, BigDecimal amount, String note) {
        Optional<String> payment = capturedPayment(reference);
        if (payment.isEmpty()) {
            return new GatewayRefund(refundId, null, "FAILED", "Razorpay has no captured payment on this order.");
        }
        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("order", reference);
        notes.put("note", note.length() > 250 ? note.substring(0, 250) : note);
        Map<String, Object> body = Map.of("amount", paise(amount), "receipt", refundId, "notes", notes);
        try {
            return parseRefund(json.readTree(call(() -> {
                try {
                    return rest.post().uri("/payments/{id}/refund", payment.get())
                            .header("X-Refund-Idempotency", refundId)
                            .contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body)).retrieve()
                            .body(String.class);
                } catch (HttpClientErrorException.Conflict e) {
                    // The same refund is still being handled: its outcome is looked up later.
                    throw new RestClientException("Refund already in progress");
                } catch (HttpClientErrorException e) {
                    String reason = reason(e, "refund");
                    if (reason.toLowerCase(java.util.Locale.ROOT).contains("duplicate")) {
                        throw new AlreadyThere();
                    }
                    throw new Refused(reason);
                }
            })), refundId);
        } catch (AlreadyThere e) {
            return lookupRefund(reference, refundId).orElseThrow(() -> unavailable());
        } catch (Refused e) {
            return new GatewayRefund(refundId, null, "FAILED", e.getMessage());
        }
    }

    /** Empty when Razorpay never received the refund, so it is safe to send again with the same id. */
    public Optional<GatewayRefund> lookupRefund(String reference, String refundId) {
        Optional<String> payment = capturedPayment(reference);
        if (payment.isEmpty()) {
            return Optional.empty();
        }
        JsonNode items = json.readTree(call(() -> rest.get()
                .uri(uri -> uri.path("/payments/{id}/refunds").queryParam("count", 100).build(payment.get()))
                .retrieve().body(String.class))).path("items");
        for (JsonNode refund : items) {
            if (refundId.equals(text(refund, "receipt"))) {
                return Optional.of(parseRefund(refund, refundId));
            }
        }
        return Optional.empty();
    }

    /** The payment our order took, which is what a refund is made against. */
    private Optional<String> capturedPayment(String reference) {
        return payments(reference).stream()
                .filter(payment -> "SUCCESS".equals(payment.status()) || "REFUNDED".equals(payment.status()))
                .map(Payment::id).findFirst();
    }

    /**
     * Razorpay signs the raw body with the webhook secret, as hex, so the body must be the bytes that arrived. Parsing
     * it first could rewrite it and break the match.
     */
    public boolean signatureMatches(byte[] body, String signature) {
        if (signature == null || !properties.webhooksConfigured()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = HexFormat.of().formatHex(mac.doFinal(body)).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, signature.trim().toLowerCase(java.util.Locale.ROOT)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static String status(String razorpay) {
        if (razorpay == null) {
            return null;
        }
        return switch (razorpay) {
            case "captured" -> "SUCCESS";
            case "failed" -> "FAILED";
            default -> razorpay.toUpperCase(java.util.Locale.ROOT);
        };
    }

    private static String refundStatus(String razorpay) {
        return switch (razorpay == null ? "" : razorpay) {
            case "processed" -> "SUCCESS";
            case "failed" -> "FAILED";
            default -> "PENDING";
        };
    }

    private GatewayRefund parseRefund(JsonNode refund, String refundId) {
        String status = refundStatus(text(refund, "status"));
        return new GatewayRefund(refundId, text(refund, "id"), status,
                "FAILED".equals(status) ? "Razorpay could not complete the refund." : null);
    }

    private static Order parseOrder(JsonNode order) {
        return new Order(text(order, "id"), text(order, "receipt"), text(order, "status"),
                order.hasNonNull("amount") ? rupees(order.get("amount").asLong()) : null);
    }

    static long paise(BigDecimal rupees) {
        return rupees.multiply(PAISE).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    static BigDecimal rupees(long paise) {
        return BigDecimal.valueOf(paise).divide(PAISE, 2, RoundingMode.UNNECESSARY);
    }

    /** Razorpay's own words for a refusal, which name no card or account. */
    private String reason(HttpClientErrorException e, String what) {
        try {
            String message = text(json.readTree(e.getResponseBodyAsString()).path("error"), "description");
            return message == null ? "Razorpay refused the " + what + "." : "Razorpay refused the " + what + ": " + message;
        } catch (RuntimeException unreadable) {
            return "Razorpay refused the " + what + ".";
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asString() : null;
    }

    private static QualityOsException unavailable() {
        return new QualityOsException(
                ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "The payment service did not respond. Please try again.");
    }

    /** The cause goes to the log by type only: its message can quote Razorpay's response. */
    private String call(Supplier<String> request) {
        try {
            return request.get();
        } catch (RestClientException e) {
            log.warn("Razorpay request failed: {}", e.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private static final class AlreadyThere extends RuntimeException {
        AlreadyThere() {
            super(null, null, false, false);
        }
    }

    private static final class Refused extends RuntimeException {
        Refused(String reason) {
            super(reason, null, false, false);
        }
    }
}
