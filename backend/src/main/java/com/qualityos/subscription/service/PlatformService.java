package com.qualityos.subscription.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.organization.service.OrganizationService;
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

    private static final String PROVIDER_MANUAL = "MANUAL";

    private final SubscriptionPlanRepository plans;
    private final PlanCatalogue catalogue;
    private final SubscriptionService subscriptions;
    private final AuditService audit;
    private final OrganizationService organizations;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    PlatformService(
            SubscriptionPlanRepository plans,
            PlanCatalogue catalogue,
            SubscriptionService subscriptions,
            AuditService audit,
            OrganizationService organizations,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.plans = plans;
        this.catalogue = catalogue;
        this.subscriptions = subscriptions;
        this.audit = audit;
        this.organizations = organizations;
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
        return inside(tenant, "RAISE_INVOICE", request.reason(), () -> subscriptions.raiseCustom(request));
    }

    /** A bank reference pays one invoice. A repeat on this one changes nothing, and elsewhere it is refused. */
    public PaymentResponse recordPayment(UUID tenant, UUID invoice, RecordPaymentRequest request) {
        Boolean elsewhere = tx.execute(status -> jdbc.queryForObject(
                "SELECT platform_reference_used_elsewhere(?, ?)", Boolean.class, request.reference(), invoice));
        if (Boolean.TRUE.equals(elsewhere)) {
            throw new ConflictException("This reference is already used on another invoice.");
        }
        return inside(tenant, () -> subscriptions.settle(invoice, PROVIDER_MANUAL, request));
    }

    public SubscriptionResponse extend(UUID tenant, int days, String reason) {
        return inside(tenant, "EXTEND", reason, () -> subscriptions.extend(days));
    }

    public SubscriptionResponse endTrial(UUID tenant, String reason) {
        return inside(tenant, "END_TRIAL", reason, subscriptions::endTrial);
    }

    public SubscriptionResponse cancel(UUID tenant, String reason) {
        return inside(tenant, "CANCEL", reason, subscriptions::cancel);
    }

    public SubscriptionResponse resume(UUID tenant, String reason) {
        return inside(tenant, "RESUME", reason, subscriptions::resume);
    }

    /** Refuses the organisation the operator is signed in to, which would lock them out of this console. */
    public void suspend(UUID tenant, String reason) {
        if (tenant.equals(TenantContext.getCurrentTenantId())) {
            throw new BusinessRuleException("You cannot suspend the organisation you are signed in to.");
        }
        inside(tenant, () -> {
            organizations.suspend(reason);
            return null;
        });
    }

    public void reactivate(UUID tenant, String reason) {
        inside(tenant, () -> {
            organizations.reactivate(reason);
            return null;
        });
    }

    /** Runs in one transaction with its audit row, in the organisation's own trail. */
    private <T> T inside(UUID tenant, String action, String reason, Supplier<T> work) {
        return inside(tenant, () -> {
            T result = work.get();
            audit.record("PLATFORM_ACTION", "Organisation", tenant, null, Map.of("action", action, "reason", reason));
            return result;
        });
    }

    private <T> T inside(UUID tenant, Supplier<T> work) {
        UUID admin = TenantContext.getCurrentUserId();
        return TenantContext.callAs(tenant, admin, () -> tx.execute(status -> work.get()));
    }
}
