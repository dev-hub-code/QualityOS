package com.qualityos.subscription.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.exception.QualityOsException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.organization.service.OrganizationService;
import com.qualityos.subscription.dto.SubscriptionDtos.CheckoutRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.CheckoutResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.PaymentGateway;
import com.qualityos.subscription.dto.SubscriptionDtos.PaymentMethod;
import com.qualityos.subscription.dto.SubscriptionDtos.RecordPaymentRequest;
import com.qualityos.subscription.entity.GatewayOrder;
import com.qualityos.subscription.entity.SubscriptionInvoice;
import com.qualityos.subscription.repository.GatewayOrderRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Online payment of an invoice. The amount always comes from the invoice, and whether money arrived always comes from
 * asking Cashfree: the browser's return and the webhook's contents only say that it is worth asking.
 * Calls to Cashfree stay outside transactions, so that a slow response holds no database lock.
 */
@Slf4j
@Service
public class CashfreePayments {

    public static final String PROVIDER = "CASHFREE";

    /** Short, because a replaced invoice cannot cancel an order that is still open at Cashfree. */
    private static final Duration ORDER_LIFETIME = Duration.ofMinutes(30);

    private static final ZoneId CHECKOUT_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Set<String> FAILED = Set.of("FAILED");
    private static final int MAX_REASON = 300;
    private static final String PAYER = "SELECT email, full_name FROM users WHERE id = ?";

    private record Payer(String email, String name) {
    }

    private record Prepared(SubscriptionInvoice invoice, Payer payer, Optional<GatewayOrder> open) {
    }

    private final CashfreeProperties properties;
    private final CashfreeClient cashfree;
    private final GatewayOrderRepository orders;
    private final SubscriptionService subscriptions;
    private final OrganizationService organizations;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final String appUrl;

    CashfreePayments(
            CashfreeProperties properties,
            CashfreeClient cashfree,
            GatewayOrderRepository orders,
            SubscriptionService subscriptions,
            OrganizationService organizations,
            AuditService audit,
            JdbcTemplate jdbc,
            JsonMapper json,
            PlatformTransactionManager transactionManager,
            @Value("${qualityos.app-url}") String appUrl) {
        this.properties = properties;
        this.cashfree = cashfree;
        this.orders = orders;
        this.subscriptions = subscriptions;
        this.organizations = organizations;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
        this.appUrl = appUrl;
    }

    public PaymentGateway gateway() {
        return new PaymentGateway(properties.configured(), properties.configured() ? properties.mode() : null);
    }

    /** Continues an open order for the invoice rather than starting another, so that it cannot be paid twice. */
    public CheckoutResponse checkout(UUID invoiceId, CheckoutRequest request) {
        requireConfigured();
        Prepared prepared = tx.execute(status -> prepare(invoiceId));
        if (prepared.open().isPresent()) {
            String reference = prepared.open().get().getGatewayOrderId();
            CashfreeClient.Order open = cashfree.order(reference);
            if ("PAID".equals(open.status())) {
                reconcile(reference);
                throw new ConflictException("This invoice has just been paid.");
            }
            if ("ACTIVE".equals(open.status()) && open.paymentSessionId() != null) {
                return new CheckoutResponse(reference, open.paymentSessionId(), properties.mode());
            }
        }
        SubscriptionInvoice invoice = prepared.invoice();
        String reference = "QOS-" + HexFormat.of().toHexDigits(UUID.randomUUID().getMostSignificantBits())
                + HexFormat.of().toHexDigits(UUID.randomUUID().getLeastSignificantBits());
        Instant expires = Instant.now().plus(ORDER_LIFETIME);
        CashfreeClient.Order created = cashfree.createOrder(new CashfreeClient.NewOrder(
                reference,
                invoice.getTotal(),
                new CashfreeClient.Customer(
                        TenantContext.getCurrentTenantId().toString().replace("-", ""),
                        prepared.payer().name(), prepared.payer().email(), request.phone()),
                UriComponentsBuilder.fromUriString(appUrl).path("/settings/billing")
                        .queryParam("order_id", reference).toUriString(),
                OffsetDateTime.ofInstant(expires, CHECKOUT_ZONE),
                "Invoice " + invoice.getInvoiceNumber()));
        tx.executeWithoutResult(status -> {
            GatewayOrder order = orders.save(new GatewayOrder(invoiceId, PROVIDER, reference, invoice.getTotal(), expires));
            audit.record("PAYMENT_CHECKOUT_STARTED", "GatewayOrder", order.getId(), null,
                    Map.of("invoice", invoice.getInvoiceNumber(), "amount", invoice.getTotal().toPlainString()));
        });
        return new CheckoutResponse(reference, created.paymentSessionId(), properties.mode());
    }

    /** Row-level security hides another organisation's order, so this reveals nothing about it. */
    public GatewayOrder.Status confirm(String reference) {
        requireConfigured();
        tx.execute(status -> orders.findByProviderAndGatewayOrderId(PROVIDER, reference))
                .orElseThrow(() -> new ResourceNotFoundException("Order", reference));
        return reconcile(reference);
    }

    /** The signature proves Cashfree sent this. It can be replayed harmlessly, because settling is idempotent. */
    public void receiveWebhook(byte[] body, String timestamp, String signature) {
        if (!properties.configured()) {
            throw new ResourceNotFoundException("Webhook", PROVIDER);
        }
        if (!cashfree.signatureMatches(body, timestamp, signature)) {
            throw new QualityOsException(ErrorCode.UNAUTHENTICATED, "The webhook signature is not valid.");
        }
        Optional<String> reference = orderIn(body);
        if (reference.isEmpty()) {
            return;
        }
        UUID tenant = TenantContext.callAs(null, null, () -> tx.execute(status -> jdbc.queryForObject(
                "SELECT gateway_order_tenant(?, ?)", UUID.class, PROVIDER, reference.get())));
        // Otherwise another order on the same Cashfree account, or the dashboard's test event.
        if (tenant != null) {
            TenantContext.runAs(tenant, null, () -> reconcile(reference.get()));
        }
    }

    private Optional<String> orderIn(byte[] body) {
        try {
            JsonNode order = json.readTree(body).path("data").path("order").path("order_id");
            return order.isString() ? Optional.of(order.asString()) : Optional.empty();
        } catch (JacksonException e) {
            throw new BusinessRuleException("The webhook body is not valid.");
        }
    }

    /** Runs under the order's tenant. A taken payment that cannot be applied is kept for a person, never dropped. */
    GatewayOrder.Status reconcile(String reference) {
        List<CashfreeClient.Payment> payments = cashfree.payments(reference);
        try {
            return tx.execute(status -> apply(reference, payments));
        } catch (ConflictException | BusinessRuleException e) {
            log.error("Cashfree order {} took a payment that could not be applied: {}", reference, e.getMessage());
            tx.executeWithoutResult(status -> {
                GatewayOrder order = orders.lockByProviderAndGatewayOrderId(PROVIDER, reference).orElseThrow();
                order.setStatus(GatewayOrder.Status.NEEDS_REVIEW);
                audit.record("PAYMENT_NEEDS_REVIEW", "GatewayOrder", order.getId(), null,
                        Map.of("reason", e.getMessage(), "amount", order.getAmount().toPlainString()));
            });
            return GatewayOrder.Status.NEEDS_REVIEW;
        }
    }

    private GatewayOrder.Status apply(String reference, List<CashfreeClient.Payment> payments) {
        GatewayOrder order = orders.lockByProviderAndGatewayOrderId(PROVIDER, reference)
                .orElseThrow(() -> new ResourceNotFoundException("Order", reference));
        if (order.getStatus() != GatewayOrder.Status.ACTIVE) {
            return order.getStatus();
        }
        if (subscriptions.invoice(order.getInvoiceId()).getStatus() == SubscriptionInvoice.Status.ISSUED) {
            payments.stream().filter(payment -> FAILED.contains(payment.status()))
                    .forEach(payment -> settle(order, payment, true));
        }
        Optional<CashfreeClient.Payment> received = payments.stream()
                .filter(payment -> "SUCCESS".equals(payment.status())).findFirst();
        if (received.isPresent()) {
            settle(order, received.get(), false);
            order.setStatus(GatewayOrder.Status.PAID);
        }
        return order.getStatus();
    }

    private void settle(GatewayOrder order, CashfreeClient.Payment payment, boolean failed) {
        BigDecimal amount = payment.amount() == null ? order.getAmount() : payment.amount();
        LocalDate today = LocalDate.now(ZoneId.of(organizations.getCurrent().timezone()));
        subscriptions.settle(order.getInvoiceId(), PROVIDER, new RecordPaymentRequest(
                payment.id(), amount, failed, failed ? reason(payment) : null,
                "upi".equalsIgnoreCase(payment.group()) ? PaymentMethod.UPI : PaymentMethod.OTHER,
                today, null));
    }

    private static String reason(CashfreeClient.Payment payment) {
        String message = payment.message() == null ? "Payment failed" : payment.message();
        return message.length() > MAX_REASON ? message.substring(0, MAX_REASON) : message;
    }

    private Prepared prepare(UUID invoiceId) {
        SubscriptionInvoice invoice = subscriptions.invoice(invoiceId);
        if (invoice.getStatus() != SubscriptionInvoice.Status.ISSUED) {
            throw new ConflictException("This invoice is not open for payment.");
        }
        Payer payer = jdbc.queryForObject(PAYER, (rows, row) -> new Payer(rows.getString(1), rows.getString(2)),
                TenantContext.getCurrentUserId());
        return new Prepared(invoice, payer, orders.findFirstByInvoiceIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(
                invoiceId, GatewayOrder.Status.ACTIVE, Instant.now()));
    }

    private void requireConfigured() {
        if (!properties.configured()) {
            throw new BusinessRuleException("Online payment is not available.");
        }
    }
}
