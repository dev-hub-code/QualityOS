package com.qualityos.subscription.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.mail.EmailTemplate;
import com.qualityos.common.persistence.DocumentNumberService;
import com.qualityos.identity.service.TenantAccessService;
import com.qualityos.common.mail.EmailContent;
import com.qualityos.notification.service.NotificationService;
import com.qualityos.notification.service.NotificationType;
import com.qualityos.organization.service.OrganizationService;
import com.qualityos.subscription.dto.SubscriptionDtos.ChangePlanRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.CustomInvoiceRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.InvoiceResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.PaymentResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.PlanResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.PublicPlan;
import com.qualityos.subscription.dto.SubscriptionDtos.RecordPaymentRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.SubscriptionResponse;
import com.qualityos.subscription.entity.PaymentTransaction;
import com.qualityos.subscription.entity.Subscription;
import com.qualityos.subscription.entity.SubscriptionInvoice;
import com.qualityos.subscription.entity.FeatureDefinition;
import com.qualityos.subscription.entity.SubscriptionPlan;
import com.qualityos.subscription.repository.FeatureDefinitionRepository;
import com.qualityos.subscription.repository.PaymentTransactionRepository;
import com.qualityos.subscription.repository.SubscriptionInvoiceRepository;
import com.qualityos.subscription.repository.SubscriptionPlanRepository;
import com.qualityos.subscription.repository.SubscriptionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A plan change or renewal raises an invoice, and takes effect only once that invoice is paid. */
@Service
public class SubscriptionService {

    private static final String LINK = "/settings/billing";
    private static final int PAYMENT_TERMS_DAYS = 7;
    private static final long MEGABYTE = 1_048_576L;
    private static final List<Integer> NOTICE_DAYS = List.of(7, 3, 1, 0);

    private final SubscriptionPlanRepository plans;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionInvoiceRepository invoices;
    private final PaymentTransactionRepository payments;
    private final DocumentNumberService numbers;
    private final OrganizationService organizations;
    private final NotificationService notifications;
    private final TenantAccessService access;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final FeatureDefinitionRepository features;
    private final String sellerGstin;
    private final String sacCode;

    SubscriptionService(
            SubscriptionPlanRepository plans,
            SubscriptionRepository subscriptions,
            SubscriptionInvoiceRepository invoices,
            PaymentTransactionRepository payments,
            DocumentNumberService numbers,
            OrganizationService organizations,
            NotificationService notifications,
            TenantAccessService access,
            JdbcTemplate jdbc,
            AuditService audit,
            FeatureDefinitionRepository features,
            @Value("${qualityos.billing.seller-gstin:}") String sellerGstin,
            @Value("${qualityos.billing.sac-code:}") String sacCode) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.invoices = invoices;
        this.payments = payments;
        this.numbers = numbers;
        this.organizations = organizations;
        this.notifications = notifications;
        this.access = access;
        this.jdbc = jdbc;
        this.audit = audit;
        this.features = features;
        this.sellerGstin = sellerGstin;
        this.sacCode = sacCode;
    }

    /** Runs inside the transaction that creates the organisation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void startTrial() {
        SubscriptionPlan plan = plans.findByCode(SubscriptionPlan.TRIAL_CODE).orElseThrow(() ->
                new IllegalStateException("The trial plan is not seeded"));
        subscriptions.save(new Subscription(plan.getId(), Instant.now().plus(Duration.ofDays(plan.getTrialDays()))));
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse current() {
        Subscription subscription = subscriptions.findCurrent().orElseThrow(SubscriptionService::missing);
        SubscriptionPlan plan = plans.findById(subscription.getPlanId()).orElseThrow();
        Instant readOnlyFrom = subscription.getCurrentPeriodEndsAt().plus(Duration.ofDays(plan.getGraceDays()));
        return new SubscriptionResponse(plan.getCode(), plan.getName(), state(subscription, plan),
                subscription.getBillingCycle(), subscription.getCurrentPeriodEndsAt(), readOnlyFrom,
                subscription.getCancelledAt(), used(PlanLimits.MEMBERS), plan.getMaxUsers(),
                used(PlanLimits.FACILITIES), plan.getMaxFacilities(), plan.getFeatures(), used(PlanLimits.STORAGE),
                plan.getMaxStorageMb(), used(PlanLimits.INSPECTIONS), plan.getMaxMonthlyInspections(),
                used(PlanLimits.ATTACHMENTS), plan.getMaxAttachments());
    }

    private static String state(Subscription subscription, SubscriptionPlan plan) {
        return switch (SubscriptionState.of(subscription.getCurrentPeriodEndsAt(), plan.getGraceDays(), Instant.now())) {
            case CURRENT -> subscription.getStatus() == Subscription.Status.TRIALING ? "TRIAL" : "ACTIVE";
            case GRACE -> "GRACE";
            case READ_ONLY -> "READ_ONLY";
        };
    }

    private long used(String count) {
        return jdbc.queryForObject("SELECT " + count, Long.class);
    }

    @Transactional(readOnly = true)
    public List<PlanResponse> activePlans() {
        return plans.findByActiveTrueOrderByDisplayOrder().stream().map(SubscriptionService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<PublicPlan> publicPlans() {
        Map<String, String> names = features.findAllByOrderByName().stream()
                .filter(FeatureDefinition::isActive)
                .collect(Collectors.toMap(FeatureDefinition::getCode, FeatureDefinition::getName));
        return plans.findByActiveTrueOrderByDisplayOrder().stream()
                .filter(SubscriptionPlan::isPublicVisible)
                .map(plan -> new PublicPlan(plan.getCode(), plan.getName(), plan.getDescription(),
                        plan.getMonthlyPrice(), plan.getAnnualPrice(), plan.getMaxUsers(), plan.getMaxFacilities(),
                        plan.getFeatures(), plan.getTrialDays(),
                        plan.getFeatures().stream().filter(names::containsKey).map(names::get).toList()))
                .toList();
    }

    static PlanResponse toResponse(SubscriptionPlan plan) {
        return new PlanResponse(plan.getId(), plan.getCode(), plan.getName(), plan.getDescription(),
                plan.getMonthlyPrice(), plan.getAnnualPrice(), plan.getTaxPercent(), plan.getMaxUsers(),
                plan.getMaxFacilities(), plan.getFeatures(), plan.getTrialDays(), plan.getGraceDays(), plan.isActive(),
                plan.getVersion(), plan.getCurrency(), plan.isPublicVisible(), plan.getArchivedAt(),
                plan.getDisplayOrder(), plan.getMaxStorageMb(), plan.getMaxMonthlyInspections(), plan.getMaxAttachments(),
                plan.getFeatureValues());
    }

    /** Also how a subscription is renewed: choose the same plan. */
    @Transactional
    public InvoiceResponse requestPlan(ChangePlanRequest request) {
        SubscriptionPlan plan = plan(request.planId());
        if (!plan.isActive()) {
            throw new BusinessRuleException("This plan is not offered any more.");
        }
        BigDecimal price = plan.priceFor(request.cycle());
        if (price == null) {
            throw new BusinessRuleException("This plan is agreed with our team. Contact us to set it up.");
        }
        return toResponse(raise(plan, request.cycle(), price));
    }

    /** Runs under the tenant being invoiced. */
    @Transactional
    /** With no amount, the plan's list price. The plan changes only once this invoice is paid. */
    public InvoiceResponse raiseCustom(CustomInvoiceRequest request) {
        SubscriptionPlan plan = plan(request.planId());
        BigDecimal amount = request.amount() != null ? request.amount() : plan.priceFor(request.cycle());
        if (amount == null) {
            throw new BusinessRuleException("This plan has no list price, so enter the agreed amount.");
        }
        return toResponse(raise(plan, request.cycle(), amount));
    }

    private SubscriptionInvoice raise(SubscriptionPlan plan, Subscription.Cycle cycle, BigDecimal amount) {
        Subscription subscription = subscriptions.lockCurrent().orElseThrow(SubscriptionService::missing);
        requireFits(plan);
        invoices.findByStatus(SubscriptionInvoice.Status.ISSUED).ifPresent(open -> {
            open.setStatus(SubscriptionInvoice.Status.VOID);
            invoices.saveAndFlush(open);
            audit.record("INVOICE_VOIDED", "SubscriptionInvoice", open.getId());
        });
        var organization = organizations.getCurrent();
        LocalDate today = LocalDate.now(ZoneId.of(organization.timezone()));
        SubscriptionInvoice invoice = new SubscriptionInvoice(subscription.getId(), numbers.next("BILL"));
        BigDecimal tax = amount.multiply(plan.getTaxPercent())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        invoice.setPlanId(plan.getId());
        invoice.setPlanName(plan.getName());
        invoice.setBillingCycle(cycle);
        invoice.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
        invoice.setTaxPercent(plan.getTaxPercent());
        invoice.setTaxAmount(tax);
        invoice.setTotal(invoice.getAmount().add(tax));
        invoice.setBuyerName(Objects.requireNonNullElse(organization.legalName(), organization.name()));
        invoice.setBuyerGstin(organization.gstin());
        invoice.setBuyerAddress(organization.address());
        GstBreakup gst = GstBreakup.of(sellerGstin, organization.gstin(), tax);
        invoice.setTaxTreatment(gst.treatment());
        invoice.setPlaceOfSupply(gst.placeOfSupply());
        invoice.setCgstAmount(gst.cgst());
        invoice.setSgstAmount(gst.sgst());
        invoice.setIgstAmount(gst.igst());
        invoice.setSacCode(sacCode.isBlank() ? null : sacCode);
        invoice.setSellerGstin(sellerGstin.isBlank() ? null : sellerGstin);
        invoice.setIssuedOn(today);
        invoice.setDueOn(today.plusDays(PAYMENT_TERMS_DAYS));
        invoices.saveAndFlush(invoice);
        audit.record("INVOICE_ISSUED", "SubscriptionInvoice", invoice.getId(), null, Map.of(
                "plan", plan.getCode(), "cycle", cycle.name(), "total", invoice.getTotal().toPlainString()));
        return invoice;
    }

    /** Nothing is taken away by a downgrade, so one that would leave the organisation over its limits waits. */
    private void requireFits(SubscriptionPlan plan) {
        if (plan.getMaxUsers() != null && used(PlanLimits.MEMBERS) > plan.getMaxUsers()) {
            throw new BusinessRuleException("The " + plan.getName() + " plan allows " + plan.getMaxUsers()
                    + " users. Remove some users or pending invitations first.");
        }
        if (plan.getMaxAttachments() != null && used(PlanLimits.ATTACHMENTS) > plan.getMaxAttachments()) {
            throw new BusinessRuleException("The " + plan.getName() + " plan allows " + plan.getMaxAttachments()
                    + " attachments. Remove some first.");
        }
        if (plan.getMaxStorageMb() != null && used(PlanLimits.STORAGE) > plan.getMaxStorageMb() * MEGABYTE) {
            throw new BusinessRuleException("The " + plan.getName() + " plan allows " + plan.getMaxStorageMb()
                    + " MB of storage. Remove some attachments first.");
        }
        if (plan.getMaxFacilities() != null && used(PlanLimits.FACILITIES) > plan.getMaxFacilities()) {
            throw new BusinessRuleException("The " + plan.getName() + " plan allows " + plan.getMaxFacilities()
                    + " facilities. Archive some first.");
        }
    }

    @Transactional
    public SubscriptionResponse cancel() {
        Subscription subscription = subscriptions.lockCurrent().orElseThrow(SubscriptionService::missing);
        if (subscription.getCancelledAt() != null) {
            throw new ConflictException("This subscription is already cancelled.");
        }
        subscription.cancel(Instant.now());
        audit.record("SUBSCRIPTION_CANCELLED", "Subscription", subscription.getId());
        subscriptions.flush();
        return current();
    }

    @Transactional
    public SubscriptionResponse resume() {
        Subscription subscription = subscriptions.lockCurrent().orElseThrow(SubscriptionService::missing);
        if (subscription.getCancelledAt() == null) {
            throw new ConflictException("This subscription is not cancelled.");
        }
        subscription.resume();
        audit.record("SUBSCRIPTION_RESUMED", "Subscription", subscription.getId());
        subscriptions.flush();
        return current();
    }

    /** Runs under the organisation being given the time, and leaves a trace that it can read. */
    @Transactional
    public SubscriptionResponse extend(int days) {
        Subscription subscription = subscriptions.lockCurrent().orElseThrow(SubscriptionService::missing);
        subscription.extend(Duration.ofDays(days));
        audit.record(subscription.getStatus() == Subscription.Status.TRIALING ? "TRIAL_EXTENDED" : "SUBSCRIPTION_EXTENDED",
                "Subscription", subscription.getId(), null, Map.of("days", days));
        subscriptions.flush();
        return current();
    }

    @Transactional
    public SubscriptionResponse endTrial() {
        Subscription subscription = subscriptions.lockCurrent().orElseThrow(SubscriptionService::missing);
        Instant now = Instant.now();
        if (subscription.getStatus() != Subscription.Status.TRIALING || !subscription.getCurrentPeriodEndsAt().isAfter(now)) {
            throw new ConflictException("This organisation is not in a running trial.");
        }
        subscription.endTrial(now);
        audit.record("TRIAL_ENDED", "Subscription", subscription.getId());
        subscriptions.flush();
        return current();
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> invoices() {
        return invoices.findAllByOrderByCreatedAtDesc().stream().map(SubscriptionService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> payments() {
        return payments.findAllByOrderByOccurredAtDesc().stream()
                .map(payment -> new PaymentResponse(payment.getId(), payment.getInvoiceId(), payment.getProvider(),
                        payment.getProviderReference(), payment.getAmount(), payment.getStatus().name(),
                        payment.getFailureReason(), payment.getOccurredAt(), payment.getMethod(), payment.getPaidOn()))
                .toList();
    }

    @Transactional(readOnly = true)
    public SubscriptionInvoice invoice(UUID id) {
        return invoices.findById(id).orElseThrow(() -> new ResourceNotFoundException("Invoice", id.toString()));
    }

    /**
     * The one way money reaches an invoice. A gateway's callback would call this too, under the tenant the invoice
     * belongs to. A repeated reference changes nothing, so a callback can safely arrive twice.
     */
    @Transactional
    public PaymentResponse settle(UUID invoiceId, String provider, RecordPaymentRequest request) {
        var repeat = payments.findByProviderAndProviderReference(provider, request.reference());
        if (repeat.isPresent()) {
            if (!repeat.get().getInvoiceId().equals(invoiceId)) {
                throw new ConflictException("This reference is already used on another invoice.");
            }
            return payments().stream().filter(p -> p.id().equals(repeat.get().getId())).findFirst().orElseThrow();
        }
        Subscription subscription = subscriptions.lockCurrent().orElseThrow(SubscriptionService::missing);
        SubscriptionInvoice invoice = invoice(invoiceId);
        if (invoice.getStatus() != SubscriptionInvoice.Status.ISSUED) {
            throw new ConflictException("This invoice is not open for payment.");
        }
        if (request.paidOn() != null
                && request.paidOn().isAfter(LocalDate.now(ZoneId.of(organizations.getCurrent().timezone())))) {
            throw new BusinessRuleException("The payment date cannot be in the future.");
        }
        PaymentTransaction payment;
        if (request.failed()) {
            payment = new PaymentTransaction(invoiceId, provider, request.reference(), request.amount(),
                    PaymentTransaction.Status.FAILED, request.failureReason());
        } else {
            if (invoice.getTotal().compareTo(request.amount()) != 0) {
                throw new BusinessRuleException("The payment must match the invoice total of " + invoice.getTotal() + ".");
            }
            payment = new PaymentTransaction(invoiceId, provider, request.reference(), request.amount(),
                    PaymentTransaction.Status.SUCCEEDED, null);
            markPaid(subscription, invoice);
        }
        payment.setMethod(request.method().name());
        payment.setPaidOn(request.paidOn());
        payment.setNotes(request.notes());
        payments.saveAndFlush(payment);
        audit.record(request.failed() ? "PAYMENT_FAILED" : "PAYMENT_RECORDED", "PaymentTransaction", payment.getId(),
                null, Map.of("invoice", invoice.getInvoiceNumber(), "provider", provider,
                        "amount", request.amount().toPlainString()));
        return payments().stream().filter(p -> p.id().equals(payment.getId())).findFirst().orElseThrow();
    }

    private void markPaid(Subscription subscription, SubscriptionInvoice invoice) {
        Instant now = Instant.now();
        UUID previousPlan = subscription.getPlanId();
        Instant start = subscription.activate(invoice.getPlanId(), invoice.getBillingCycle(), now);
        if (!previousPlan.equals(invoice.getPlanId())) {
            notifications.notify(access.holdersOf(PlanAccess.SUBSCRIPTION_MANAGE), NotificationType.PLAN_CHANGED,
                    "Plan changed", "Your organisation is now on the " + invoice.getPlanName() + " plan.", LINK);
        }
        invoice.setStatus(SubscriptionInvoice.Status.PAID);
        invoice.setPaidAt(now);
        invoice.setPeriodStartsAt(start);
        invoice.setPeriodEndsAt(subscription.getCurrentPeriodEndsAt());
        notifications.notify(access.holdersOf(PlanAccess.SUBSCRIPTION_MANAGE), NotificationType.PAYMENT_RECEIVED,
                "Payment received", "Thank you. Invoice " + invoice.getInvoiceNumber() + " is paid.", LINK,
                new EmailContent(EmailTemplate.PAYMENT_CONFIRMATION, Map.of("amount", "₹" + invoice.getTotal())));
    }

    /** Sends the one notice now due, if any. Runs under the current tenant. */
    @Transactional
    /** Each invoice is chased once. A paid or replaced invoice is no longer open, so nothing goes out for it. */
    public int sendOverdueNotice() {
        LocalDate today = LocalDate.now(ZoneId.of(organizations.getCurrent().timezone()));
        return invoices.findByStatus(SubscriptionInvoice.Status.ISSUED)
                .filter(invoice -> invoice.getOverdueNotifiedAt() == null && invoice.getDueOn().isBefore(today))
                .map(invoice -> {
                    invoice.setOverdueNotifiedAt(Instant.now());
                    notifications.notify(access.holdersOf(PlanAccess.SUBSCRIPTION_MANAGE), NotificationType.INVOICE_OVERDUE,
                            "Invoice overdue", "Invoice " + invoice.getInvoiceNumber() + " was due on "
                                    + invoice.getDueOn() + ". Please pay it to keep your subscription.", LINK);
                    return 1;
                })
                .orElse(0);
    }

    public int sendExpiryNotice() {
        Subscription subscription = subscriptions.lockCurrent().orElseThrow(SubscriptionService::missing);
        SubscriptionPlan plan = plans.findById(subscription.getPlanId()).orElseThrow();
        Instant endsAt = subscription.getCurrentPeriodEndsAt();
        Instant now = Instant.now();
        long left = Duration.between(now, endsAt).toDays();
        Integer stage;
        String body;
        if (SubscriptionState.of(endsAt, plan.getGraceDays(), now) == SubscriptionState.READ_ONLY) {
            stage = -plan.getGraceDays();
            body = "Your subscription has ended, so QualityOS is now read-only. All records are safe and can be read"
                    + " and exported. Renew to carry on working.";
        } else if (now.isBefore(endsAt)) {
            stage = NOTICE_DAYS.stream().filter(days -> left < days).reduce((first, second) -> second).orElse(null);
            body = "Your subscription ends soon. Renew to keep working without a break.";
        } else {
            stage = 0;
            body = "Your subscription has ended. QualityOS turns read-only after " + plan.getGraceDays() + " days.";
        }
        Integer sent = subscription.getLastNoticeDays();
        if (stage == null || (sent != null && sent <= stage)) {
            return 0;
        }
        subscription.setLastNoticeDays(stage);
        notifications.notify(access.holdersOf(PlanAccess.SUBSCRIPTION_MANAGE), NotificationType.SUBSCRIPTION_EXPIRY,
                "Subscription ending", body, LINK, new EmailContent(EmailTemplate.SUBSCRIPTION_EXPIRY, Map.of(
                        "dueDate", LocalDate.ofInstant(endsAt, ZoneId.of(organizations.getCurrent().timezone())).toString())));
        return 1;
    }

    private SubscriptionPlan plan(UUID id) {
        return plans.findById(id).orElseThrow(() -> new ResourceNotFoundException("Plan", id.toString()));
    }

    private static ResourceNotFoundException missing() {
        return new ResourceNotFoundException("Subscription", "current");
    }

    static InvoiceResponse toResponse(SubscriptionInvoice invoice) {
        return new InvoiceResponse(invoice.getId(), invoice.getInvoiceNumber(), invoice.getPlanName(),
                invoice.getBillingCycle(), invoice.getAmount(), invoice.getTaxPercent(), invoice.getTaxAmount(),
                invoice.getTotal(), invoice.getStatus().name(), invoice.getIssuedOn(), invoice.getDueOn(),
                invoice.getPaidAt(), invoice.getTaxTreatment(), invoice.getPlaceOfSupply(), invoice.getCgstAmount(),
                invoice.getSgstAmount(), invoice.getIgstAmount(), invoice.getSacCode(), invoice.getSellerGstin(),
                invoice.getBuyerName(), invoice.getBuyerGstin(), invoice.getPeriodStartsAt(), invoice.getPeriodEndsAt());
    }
}
