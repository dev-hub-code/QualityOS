package com.qualityos.subscription.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.mail.EmailContent;
import com.qualityos.common.mail.EmailTemplate;
import com.qualityos.common.persistence.DocumentNumberService;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.notification.service.NotificationService;
import com.qualityos.notification.service.NotificationType;
import com.qualityos.organization.service.OrganizationService;
import com.qualityos.subscription.dto.SubscriptionDtos.CreditNoteRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.CreditNoteResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.RefundRoute;
import com.qualityos.subscription.dto.SubscriptionDtos.RefundView;
import com.qualityos.subscription.entity.CreditNote;
import com.qualityos.subscription.entity.GatewayOrder;
import com.qualityos.subscription.entity.Refund;
import com.qualityos.subscription.entity.Subscription;
import com.qualityos.subscription.entity.SubscriptionInvoice;
import com.qualityos.subscription.repository.CreditNoteRepository;
import com.qualityos.subscription.repository.GatewayOrderRepository;
import com.qualityos.subscription.repository.RefundRepository;
import com.qualityos.subscription.repository.SubscriptionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Takes back money once taken. A credit note reverses part or all of a paid invoice, with its GST split as the invoice
 * was, and its refund goes back the way the money came or by bank. A payment the gateway took that no invoice could
 * take is refunded whole, without a credit note, because it was never revenue. Everything here runs under the
 * organisation concerned.
 */
@Service
public class BillingRefunds {

    private static final String LINK = "/settings/billing";

    private final CreditNoteRepository creditNotes;
    private final RefundRepository refunds;
    private final GatewayOrderRepository orders;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionService billing;
    private final CashfreeClient cashfree;
    private final DocumentNumberService numbers;
    private final OrganizationService organizations;
    private final NotificationService notifications;
    private final TenantAccessService access;
    private final AuditService audit;
    private final InvoicePdfRenderer renderer;

    /** A credit note's PDF and the name to save it under. */
    public record Pdf(String fileName, byte[] content) {
    }

    BillingRefunds(
            CreditNoteRepository creditNotes,
            RefundRepository refunds,
            GatewayOrderRepository orders,
            SubscriptionRepository subscriptions,
            SubscriptionService billing,
            CashfreeClient cashfree,
            DocumentNumberService numbers,
            OrganizationService organizations,
            NotificationService notifications,
            TenantAccessService access,
            AuditService audit,
            InvoicePdfRenderer renderer) {
        this.renderer = renderer;
        this.creditNotes = creditNotes;
        this.refunds = refunds;
        this.orders = orders;
        this.subscriptions = subscriptions;
        this.billing = billing;
        this.cashfree = cashfree;
        this.numbers = numbers;
        this.organizations = organizations;
        this.notifications = notifications;
        this.access = access;
        this.audit = audit;
    }

    /**
     * Never more than is left of the invoice. Taking back all that is left takes back exactly the GST left too, so
     * rounding never leaves a paisa behind. The subscription is locked, so two credit notes on one invoice queue.
     */
    @Transactional
    public CreditNoteResponse issue(UUID invoiceId, CreditNoteRequest request) {
        Subscription subscription = subscriptions.lockCurrent()
                .orElseThrow(() -> new ResourceNotFoundException("Subscription", "current"));
        SubscriptionInvoice invoice = billing.invoice(invoiceId);
        if (invoice.getStatus() != SubscriptionInvoice.Status.PAID) {
            throw new BusinessRuleException("Only a paid invoice can be credited.");
        }
        LocalDate today = today();
        List<CreditNote> earlier = creditNotes.findByInvoiceId(invoiceId);
        BigDecimal creditedAmount = earlier.stream().map(CreditNote::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditedTax = earlier.stream().map(CreditNote::getTaxAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal left = invoice.getAmount().subtract(creditedAmount);
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(left) > 0) {
            throw new BusinessRuleException("Only ₹" + left.toPlainString() + " of this invoice is left to credit, before GST.");
        }
        BigDecimal tax = amount.compareTo(left) == 0
                ? invoice.getTaxAmount().subtract(creditedTax)
                : amount.multiply(invoice.getTaxPercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal cgst = null;
        BigDecimal sgst = null;
        BigDecimal igst = null;
        if (invoice.getCgstAmount() != null) {
            cgst = tax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            sgst = tax.subtract(cgst);
        } else if (invoice.getIgstAmount() != null) {
            igst = tax;
        }
        if (request.endAccess() && !Objects.equals(invoice.getPeriodEndsAt(), subscription.getCurrentPeriodEndsAt())) {
            throw new BusinessRuleException("This invoice is not for the period being paid for now, so it cannot end it.");
        }
        Optional<GatewayOrder> paidThrough = request.refundBy() == RefundRoute.GATEWAY
                ? orders.findByInvoiceIdAndStatusInOrderByCreatedAtDesc(invoiceId, List.of(GatewayOrder.Status.PAID))
                        .stream().findFirst()
                : Optional.empty();
        if (request.refundBy() == RefundRoute.GATEWAY && paidThrough.isEmpty()) {
            throw new BusinessRuleException("This invoice was not paid through the gateway. Refund it by bank.");
        }
        if (request.refundBy() == RefundRoute.BANK) {
            requireBankDetails(request, today);
        }
        CreditNote note = creditNotes.saveAndFlush(new CreditNote(numbers.next("CN"), invoiceId, amount, tax, cgst,
                sgst, igst, request.reason().trim(), today, request.endAccess()));
        Refund refund = request.refundBy() == RefundRoute.BANK
                ? Refund.byHand(note.getId(), request.reference().trim(), note.getTotal(), request.refundedOn())
                : Refund.throughGateway(note.getId(), null, note.getTotal());
        save(refund);
        if (paidThrough.isPresent()) {
            send(refund, paidThrough.get(), "Credit note " + note.getCreditNoteNumber());
        }
        if (request.endAccess()) {
            subscription.endPaidPeriod(Instant.now());
            subscriptions.saveAndFlush(subscription);
        }
        audit.record("CREDIT_NOTE_ISSUED", "CreditNote", note.getId(), null, Map.of(
                "number", note.getCreditNoteNumber(), "invoice", invoice.getInvoiceNumber(),
                "total", note.getTotal().toPlainString(), "refundBy", refund.getProvider(),
                "endsAccess", request.endAccess(), "reason", note.getReason()));
        notifications.notify(access.holdersOf(PlanAccess.SUBSCRIPTION_MANAGE), NotificationType.REFUND_ISSUED,
                "Refund issued", "Credit note " + note.getCreditNoteNumber() + " refunds ₹" + note.getTotal()
                        + " of invoice " + invoice.getInvoiceNumber() + ".", LINK,
                new EmailContent(EmailTemplate.REFUND_ISSUED, Map.of("amount", "₹" + note.getTotal(),
                        "creditNoteNumber", note.getCreditNoteNumber(), "invoiceNumber", invoice.getInvoiceNumber())));
        return responses(List.of(note)).getFirst();
    }

    /** Refunds, whole, a payment that no invoice could take. */
    @Transactional
    public RefundView refundUnapplied(UUID orderId, String reason) {
        GatewayOrder order = orders.findById(orderId).orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        if (order.getStatus() != GatewayOrder.Status.NEEDS_REVIEW) {
            throw new ConflictException("Only a payment waiting for review can be refunded this way.");
        }
        Refund refund = save(Refund.throughGateway(null, order.getId(), order.getAmount()));
        send(refund, order, reason.trim());
        order.setStatus(GatewayOrder.Status.REFUNDED);
        orders.saveAndFlush(order);
        audit.record("PAYMENT_REFUNDED", "GatewayOrder", order.getId(), null, Map.of(
                "order", order.getGatewayOrderId(), "amount", order.getAmount().toPlainString(), "reason", reason.trim()));
        return view(refund);
    }

    /** Asks the gateway where a refund stands. Changes nothing once it went through or failed. */
    @Transactional
    public RefundView refresh(UUID refundId) {
        Refund refund = refunds.findById(refundId).orElseThrow(() -> new ResourceNotFoundException("Refund", refundId));
        if (Refund.GATEWAY.equals(refund.getProvider()) && refund.getStatus() == Refund.Status.PENDING) {
            update(refund, cashfree.findRefund(orderOf(refund).getGatewayOrderId(), refund.getRefundReference()));
        }
        return view(refund);
    }

    /** From the gateway's webhook, under the organisation the order belongs to. An unknown refund is not ours. */
    @Transactional
    public void refreshByReference(String gatewayOrderId, String refundReference) {
        refunds.findByProviderAndRefundReference(Refund.GATEWAY, refundReference)
                .ifPresent(refund -> update(refund, cashfree.findRefund(gatewayOrderId, refundReference)));
    }

    @Transactional(readOnly = true)
    public List<CreditNoteResponse> list() {
        return responses(creditNotes.findAllByOrderByCreatedAtDesc());
    }

    @Transactional(readOnly = true)
    public CreditNote creditNote(UUID id) {
        return creditNotes.findById(id).orElseThrow(() -> new ResourceNotFoundException("Credit note", id));
    }

    @Transactional(readOnly = true)
    public Pdf pdf(UUID id) {
        CreditNote note = creditNote(id);
        Refund refund = refunds.findByCreditNoteIdIn(List.of(id)).stream().findFirst().orElse(null);
        return new Pdf(note.getCreditNoteNumber() + ".pdf",
                renderer.render(note, billing.invoice(note.getInvoiceId()), refund));
    }

    private void requireBankDetails(CreditNoteRequest request, LocalDate today) {
        if (!StringUtils.hasText(request.reference()) || request.refundedOn() == null) {
            throw new BusinessRuleException("A refund by bank needs the bank's reference and the date it was made.");
        }
        if (request.refundedOn().isAfter(today)) {
            throw new BusinessRuleException("The refund date cannot be in the future.");
        }
        if (refunds.existsByProviderAndRefundReferenceIgnoreCase(Refund.MANUAL, request.reference().trim())) {
            throw new ConflictException("This bank reference is already recorded on another refund.");
        }
    }

    private Refund save(Refund refund) {
        return refunds.saveAndFlush(refund);
    }

    private void send(Refund refund, GatewayOrder order, String note) {
        update(refund, cashfree.refund(order.getGatewayOrderId(), refund.getRefundReference(), refund.getAmount(), note));
    }

    private void update(Refund refund, CashfreeClient.GatewayRefund reported) {
        if (refund.update(reported.status(), reported.gatewayRefundId(), reported.message(), today())) {
            audit.record(refund.getStatus() == Refund.Status.SUCCEEDED ? "REFUND_SUCCEEDED" : "REFUND_FAILED",
                    "Refund", refund.getId(), null, Map.of("amount", refund.getAmount().toPlainString()));
        }
        refunds.saveAndFlush(refund);
    }

    private GatewayOrder orderOf(Refund refund) {
        if (refund.getGatewayOrderId() != null) {
            return orders.findById(refund.getGatewayOrderId()).orElseThrow();
        }
        UUID invoiceId = creditNote(refund.getCreditNoteId()).getInvoiceId();
        return orders.findByInvoiceIdAndStatusInOrderByCreatedAtDesc(invoiceId, List.of(GatewayOrder.Status.PAID))
                .stream().findFirst().orElseThrow();
    }

    private List<CreditNoteResponse> responses(List<CreditNote> found) {
        Map<UUID, Refund> byNote = refunds.findByCreditNoteIdIn(found.stream().map(CreditNote::getId).toList()).stream()
                .collect(Collectors.toMap(Refund::getCreditNoteId, Function.identity()));
        return found.stream().map(note -> new CreditNoteResponse(
                note.getId(), note.getCreditNoteNumber(), note.getInvoiceId(),
                billing.invoice(note.getInvoiceId()).getInvoiceNumber(), note.getAmount(), note.getTaxAmount(),
                note.getTotal(), note.getReason(), note.getIssuedOn(), note.isEndsAccess(),
                Optional.ofNullable(byNote.get(note.getId())).map(BillingRefunds::view).orElse(null))).toList();
    }

    private static RefundView view(Refund refund) {
        return new RefundView(refund.getId(), refund.getProvider(), refund.getRefundReference(), refund.getAmount(),
                refund.getStatus().name(), refund.getFailureReason(), refund.getRefundedOn());
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of(organizations.getCurrent().timezone()));
    }
}
