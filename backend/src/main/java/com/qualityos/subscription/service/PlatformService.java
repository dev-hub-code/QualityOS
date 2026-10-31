package com.qualityos.subscription.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.organization.service.OrganizationService;
import com.qualityos.subscription.dto.SubscriptionDtos.CustomInvoiceRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.InvoiceResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.OrganisationList;
import com.qualityos.subscription.dto.SubscriptionDtos.PaymentResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.PlanResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.RecordPaymentRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.SavePlanRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.SubscriptionResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.TenantSubscription;
import com.qualityos.subscription.entity.SubscriptionPlan;
import com.qualityos.subscription.repository.SubscriptionPlanRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The platform console. It never turns row-level security off: each organisation is read and changed under that
 * organisation's own tenant context, so its audit trail shows what the platform did. Must run with no open transaction.
 */
@Service
public class PlatformService {

    private static final int MAX_ORGANISATIONS = 500;
    private static final String PROVIDER_MANUAL = "MANUAL";

    private final SubscriptionPlanRepository plans;
    private final SubscriptionService subscriptions;
    private final OrganizationService organizations;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    PlatformService(
            SubscriptionPlanRepository plans,
            SubscriptionService subscriptions,
            OrganizationService organizations,
            AuditService audit,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.organizations = organizations;
        this.audit = audit;
        this.jdbc = jdbc;
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
            if (plan.getVersion() != request.version()) {
                throw new ConflictException("Someone else has changed this plan. Reload and try again.");
            }
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
            plans.saveAndFlush(plan);
            audit.record("PLAN_UPDATED", "SubscriptionPlan", plan.getId(), before, figures(plan));
            return SubscriptionService.toResponse(plan);
        });
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

    public OrganisationList organisations() {
        List<UUID> tenants = tx.execute(status -> jdbc.queryForList("SELECT platform_tenant_ids()", UUID.class));
        return new OrganisationList(tenants.size(), tenants.stream()
                .limit(MAX_ORGANISATIONS)
                .map(tenant -> inside(tenant, () -> new TenantSubscription(tenant, organizations.getCurrent().name(),
                        subscriptions.current(),
                        subscriptions.invoices().stream().filter(invoice -> invoice.status().equals("ISSUED")).toList())))
                .toList());
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
