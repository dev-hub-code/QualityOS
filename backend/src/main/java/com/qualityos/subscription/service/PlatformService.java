package com.qualityos.subscription.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.subscription.dto.SubscriptionDtos.CustomInvoiceRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.InvoiceResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.PaymentResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.PlanResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.RecordPaymentRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.SavePlanRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.SubscriptionResponse;
import com.qualityos.subscription.entity.SubscriptionPlan;
import com.qualityos.subscription.repository.SubscriptionPlanRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The platform console. It never turns row-level security off: each organisation is read and changed under that
 * organisation's own tenant context, so its audit trail shows what the platform did. Must run with no open transaction.
 */
@Service
public class PlatformService {

    private static final String PROVIDER_MANUAL = "MANUAL";

    private final SubscriptionPlanRepository plans;
    private final PlanCatalogue catalogue;
    private final SubscriptionService subscriptions;
    private final AuditService audit;
    private final TransactionTemplate tx;

    PlatformService(
            SubscriptionPlanRepository plans,
            PlanCatalogue catalogue,
            SubscriptionService subscriptions,
            AuditService audit,
            PlatformTransactionManager transactionManager) {
        this.plans = plans;
        this.catalogue = catalogue;
        this.subscriptions = subscriptions;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public List<PlanResponse> plans() {
        return tx.execute(status -> plans.findAllByOrderByDisplayOrder().stream()
                .map(SubscriptionService::toResponse)
                .toList());
    }

    /** Existing invoices keep the figures they were issued with. */
    public PlanResponse savePlan(UUID id, SavePlanRequest request) {
        return tx.execute(status -> {
            SubscriptionPlan plan = plans.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Plan", id.toString()));
            catalogue.requireCurrent(plan, request.version());
            catalogue.requireReasonWhenInUse(plan, request.reason());
            if (!PlanAccess.FEATURES.keySet().containsAll(request.features())) {
                throw new BusinessRuleException("A plan can only include known features.");
            }
            Map<String, Object> before = figures(plan);
            plan.setName(request.name().trim());
            plan.setDescription(request.description().trim());
            plan.setMonthlyPrice(request.monthlyPrice());
            plan.setAnnualPrice(request.annualPrice());
            plan.setTaxPercent(request.taxPercent());
            plan.setMaxUsers(request.maxUsers());
            plan.setMaxFacilities(request.maxFacilities());
            plan.setFeatures(new ArrayList<>(request.features()));
            plan.setTrialDays(request.trialDays());
            plan.setGraceDays(request.graceDays());
            plan.setActive(request.active());
            if (request.publicVisible() != null) {
                plan.setPublicVisible(request.publicVisible());
            }
            if (request.displayOrder() != null) {
                plan.setDisplayOrder(request.displayOrder());
            }
            plans.saveAndFlush(plan);
            catalogue.remember(plan, request.reason());
            audit.record("PLAN_UPDATED", "SubscriptionPlan", plan.getId(), before, figures(plan, request.reason()));
            return SubscriptionService.toResponse(plan);
        });
    }

    private static Map<String, Object> figures(SubscriptionPlan plan, String reason) {
        Map<String, Object> figures = new HashMap<>(figures(plan));
        figures.put("reason", reason == null ? "" : reason);
        return figures;
    }

    private static Map<String, Object> figures(SubscriptionPlan plan) {
        return Map.of(
                "monthlyPrice", String.valueOf(plan.getMonthlyPrice()),
                "annualPrice", String.valueOf(plan.getAnnualPrice()),
                "taxPercent", plan.getTaxPercent().toPlainString(),
                "maxUsers", String.valueOf(plan.getMaxUsers()),
                "maxFacilities", String.valueOf(plan.getMaxFacilities()),
                "features", String.join(",", plan.getFeatures()),
                "active", plan.isActive());
    }

    public InvoiceResponse raiseInvoice(UUID tenant, CustomInvoiceRequest request) {
        return inside(tenant, () -> subscriptions.raiseCustom(request));
    }

    public PaymentResponse recordPayment(UUID tenant, UUID invoice, RecordPaymentRequest request) {
        return inside(tenant, () -> subscriptions.settle(invoice, PROVIDER_MANUAL, request));
    }

    public SubscriptionResponse extend(UUID tenant, int days) {
        return inside(tenant, () -> subscriptions.extend(days));
    }

    private <T> T inside(UUID tenant, Supplier<T> work) {
        UUID admin = TenantContext.getCurrentUserId();
        return TenantContext.callAs(tenant, admin, () -> tx.execute(status -> work.get()));
    }
}
