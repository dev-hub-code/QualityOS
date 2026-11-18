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

    /**
     * How long an order may stay reserved without Cashfree knowing it before it is taken for abandoned: longer than a
     * create call can take (CashfreeClient's connect and read timeouts).
     */
    private static final Duration CREATE_GRACE = Duration.ofMinutes(2);

    private static final String CURRENCY = "INR";

    /**
     * @param order the order reserved by this request when {@code reserved}, else one already open or being created
     */
    private record Prepared(SubscriptionInvoice invoice, Payer payer, GatewayOrder order, boolean reserved) {
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
    private final BillingRefunds refunds;

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
            @Value("${qualityos.app-url}") String appUrl,
            BillingRefunds refunds) {
        this.refunds = refunds;
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

    /**
     * Continues the invoice's open order rather than starting another, so that it cannot be paid twice. The order is
     * reserved under the invoice's row lock before Cashfree is asked to create it: a second checkout at the same moment
     * finds the reservation and shares that order, or is asked to try again while it is being created. If the
     * application stops after Cashfree created an order but before recording it, the next checkout finds the order at
     * Cashfree and carries on with it.
     */
    public CheckoutResponse checkout(UUID invoiceId, CheckoutRequest request) {
        requireConfigured();
        // Twice at most: once more after setting aside an order Cashfree no longer has open.
        for (int attempt = 0; attempt < 2; attempt++) {
            Prepared prepared = tx.execute(status -> prepare(invoiceId));
            if (prepared.reserved()) {
                return create(prepared, request);
            }
            GatewayOrder open = prepared.order();
            String reference = open.getGatewayOrderId();
            Optional<CashfreeClient.Order> found = cashfree.findOrder(reference);
            if (found.isPresent() && "PAID".equals(found.get().status())) {
                opened(reference);
                reconcile(reference);
                throw new ConflictException("This invoice has just been paid.");
            }
            if (found.isPresent() && "ACTIVE".equals(found.get().status()) && found.get().paymentSessionId() != null) {
                opened(reference);
                return new CheckoutResponse(reference, found.get().paymentSessionId(), properties.mode());
            }
            if (found.isEmpty() && open.getStatus() == GatewayOrder.Status.CREATING
                    && open.getCreatedAt().isAfter(Instant.now().minus(CREATE_GRACE))) {
                throw new ConflictException("A payment for this invoice is being started. Try again in a moment.");
            }
            // Expired or ended at Cashfree, or never created there: nothing more can be paid on it.
            abandon(reference);
        }
        throw new ConflictException("A payment for this invoice is being started. Try again in a moment.");
    }

    private CheckoutResponse create(Prepared prepared, CheckoutRequest request) {
        String reference = prepared.order().getGatewayOrderId();
        CashfreeClient.Order created;
        try {
            created = cashfree.createOrder(newOrder(prepared, request));
        } catch (RuntimeException e) {
            // Cashfree may have created it before the failure. If it plainly has not, the next checkout need not wait.
            try {
                if (cashfree.findOrder(reference).isEmpty()) {
                    abandon(reference);
                }
            } catch (RuntimeException unknown) {
                // Still unknown: the next checkout settles the reservation.
            }
            throw e;
        }
        opened(reference);
        return new CheckoutResponse(reference, created.paymentSessionId(), properties.mode());
    }

    private CashfreeClient.NewOrder newOrder(Prepared prepared, CheckoutRequest request) {
        SubscriptionInvoice invoice = prepared.invoice();
        GatewayOrder reserved = prepared.order();
        String reference = reserved.getGatewayOrderId();
        return new CashfreeClient.NewOrder(
                reference,
                invoice.getTotal(),
                new CashfreeClient.Customer(
                        TenantContext.getCurrentTenantId().toString().replace("-", ""),
                        prepared.payer().name(), prepared.payer().email(), request.phone()),
                UriComponentsBuilder.fromUriString(appUrl).path("/settings/billing")
                        .queryParam("order_id", reference).toUriString(),
                OffsetDateTime.ofInstant(reserved.getExpiresAt(), CHECKOUT_ZONE),
                "Invoice " + invoice.getInvoiceNumber());
    }

    /** Cashfree holds the order: a reservation becomes open. Audited once, when it does. */
    private void opened(String reference) {
        tx.executeWithoutResult(status -> {
            GatewayOrder order = orders.lockByProviderAndGatewayOrderId(PROVIDER, reference).orElseThrow();
            if (order.getStatus() == GatewayOrder.Status.CREATING) {
                order.setStatus(GatewayOrder.Status.ACTIVE);
                audit.record("PAYMENT_CHECKOUT_STARTED", "GatewayOrder", order.getId(), null,
                        Map.of("invoice", subscriptions.invoice(order.getInvoiceId()).getInvoiceNumber(),
                                "amount", order.getAmount().toPlainString()));
            }
        });
    }

    private void abandon(String reference) {
        tx.executeWithoutResult(status -> {
            GatewayOrder order = orders.lockByProviderAndGatewayOrderId(PROVIDER, reference).orElseThrow();
            if (order.getStatus() == GatewayOrder.Status.CREATING || order.getStatus() == GatewayOrder.Status.ACTIVE) {
                order.setStatus(GatewayOrder.Status.ABANDONED);
            }
        });
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
        Optional<String[]> refund = refundIn(body);
        if (refund.isPresent()) {
            UUID owner = TenantContext.callAs(null, null, () -> tx.execute(status -> jdbc.queryForObject(
                    "SELECT gateway_order_tenant(?, ?)", UUID.class, PROVIDER, refund.get()[0])));
            if (owner != null) {
                TenantContext.runAs(owner, null, () -> refunds.refreshByReference(refund.get()[0], refund.get()[1]));
            }
            return;
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

    /** The order and our refund id, when this is news of a refund. Its status is then asked of Cashfree. */
    private Optional<String[]> refundIn(byte[] body) {
        try {
            JsonNode refund = json.readTree(body).path("data").path("refund");
            JsonNode order = refund.path("order_id");
            JsonNode id = refund.path("refund_id");
            return order.isString() && id.isString()
                    ? Optional.of(new String[] {order.asString(), id.asString()}) : Optional.empty();
        } catch (JacksonException e) {
            throw new BusinessRuleException("The webhook body is not valid.");
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

    private static final java.util.Set<GatewayOrder.Status> SETTLED = java.util.EnumSet.of(GatewayOrder.Status.PAID,
            GatewayOrder.Status.NEEDS_REVIEW, GatewayOrder.Status.REFUND_PENDING, GatewayOrder.Status.REFUNDED);

    private GatewayOrder.Status apply(String reference, List<CashfreeClient.Payment> payments) {
        GatewayOrder order = orders.lockByProviderAndGatewayOrderId(PROVIDER, reference)
                .orElseThrow(() -> new ResourceNotFoundException("Order", reference));
        // An abandoned or still-reserved order can take money all the same: it is applied, never dropped. One already
        // settled, waiting for review, or being or already refunded is never applied again, however often Cashfree
        // tells us about it.
        if (SETTLED.contains(order.getStatus())) {
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

    /**
     * A successful payment must say how much was paid, in rupees: one that does not is kept for a person rather than
     * taken as the invoice amount. A failed one moved no money, so the order's amount stands in for a missing figure.
     */
    private void settle(GatewayOrder order, CashfreeClient.Payment payment, boolean failed) {
        if (!failed && payment.amount() == null) {
            throw new BusinessRuleException("Cashfree did not report how much payment " + payment.id() + " was.");
        }
        if (!failed && payment.currency() != null && !CURRENCY.equals(payment.currency())) {
            throw new BusinessRuleException("Payment " + payment.id() + " was in " + payment.currency()
                    + ", not " + CURRENCY + ".");
        }
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

    /**
     * Under the invoice's row lock: the order already open or being created, or a new one reserved. A checkout at the
     * same moment waits here, then finds this one.
     */
    private Prepared prepare(UUID invoiceId) {
        // Row-level security hides another organisation's invoice, so this finds nothing to lock there.
        jdbc.query("SELECT id FROM subscription_invoices WHERE id = ? FOR UPDATE", (rows, row) -> null, invoiceId);
        SubscriptionInvoice invoice = subscriptions.invoice(invoiceId);
        if (invoice.getStatus() != SubscriptionInvoice.Status.ISSUED) {
            throw new ConflictException("This invoice is not open for payment.");
        }
        Payer payer = jdbc.queryForObject(PAYER, (rows, row) -> new Payer(rows.getString(1), rows.getString(2)),
                TenantContext.getCurrentUserId());
        Instant now = Instant.now();
        Optional<GatewayOrder> open = orders.findByInvoiceIdAndStatusInOrderByCreatedAtDesc(invoiceId,
                        List.of(GatewayOrder.Status.CREATING, GatewayOrder.Status.ACTIVE)).stream()
                .filter(order -> order.getStatus() == GatewayOrder.Status.CREATING || order.getExpiresAt().isAfter(now))
                .findFirst();
        if (open.isPresent()) {
            return new Prepared(invoice, payer, open.get(), false);
        }
        String reference = "QOS-" + HexFormat.of().toHexDigits(UUID.randomUUID().getMostSignificantBits())
                + HexFormat.of().toHexDigits(UUID.randomUUID().getLeastSignificantBits());
        GatewayOrder reserved = orders.saveAndFlush(
                new GatewayOrder(invoiceId, PROVIDER, reference, invoice.getTotal(), now.plus(ORDER_LIFETIME)));
        return new Prepared(invoice, payer, reserved, true);
    }

    private void requireConfigured() {
        if (!properties.configured()) {
            throw new BusinessRuleException("Online payment is not available.");
        }
    }
}
