package com.qualityos.subscription.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.common.tenant.TenantContext;
import com.qualityos.common.util.UuidV7;
import com.qualityos.subscription.dto.SubscriptionDtos.CreatePlanRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.PlanActionRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.PlanActiveRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.PlanImpact;
import com.qualityos.subscription.dto.SubscriptionDtos.PlanResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.PlanVersion;
import com.qualityos.subscription.entity.FeatureDefinition;
import com.qualityos.subscription.entity.SubscriptionPlan;
import com.qualityos.subscription.repository.FeatureDefinitionRepository;
import com.qualityos.subscription.repository.SubscriptionPlanRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The plans on offer, and their history. A plan's limits and features reach its subscribers at once, and a price
 * reaches them on their next invoice, so a change to a plan in use must say why.
 */
@Service
public class PlanCatalogue {

    private final SubscriptionPlanRepository plans;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final FeatureDefinitionRepository features;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    PlanCatalogue(
            SubscriptionPlanRepository plans,
            AuditService audit,
            JdbcTemplate jdbc,
            FeatureDefinitionRepository features,
            JsonMapper json,
            PlatformTransactionManager transactionManager) {
        this.plans = plans;
        this.audit = audit;
        this.jdbc = jdbc;
        this.features = features;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public PlanResponse create(CreatePlanRequest request) {
        return tx.execute(status -> add(withDefaultFeatures(new SubscriptionPlan(request.code(), request.name().trim())), "PLAN_CREATED"));
    }

    private SubscriptionPlan withDefaultFeatures(SubscriptionPlan plan) {
        plan.setFeatures(new ArrayList<>(features.findAllByOrderByName().stream()
                .filter(feature -> feature.isActive() && feature.isDefaultEnabled())
                .map(FeatureDefinition::getCode)
                .toList()));
        return plan;
    }

    public PlanResponse duplicate(UUID id, CreatePlanRequest request) {
        return tx.execute(status -> {
            SubscriptionPlan source = find(id);
            SubscriptionPlan copy = new SubscriptionPlan(request.code(), request.name().trim());
            copy.setDescription(source.getDescription());
            copy.setMonthlyPrice(source.getMonthlyPrice());
            copy.setAnnualPrice(source.getAnnualPrice());
            copy.setTaxPercent(source.getTaxPercent());
            copy.setMaxUsers(source.getMaxUsers());
            copy.setMaxFacilities(source.getMaxFacilities());
            copy.setMaxStorageMb(source.getMaxStorageMb());
            copy.setMaxMonthlyInspections(source.getMaxMonthlyInspections());
            copy.setMaxAttachments(source.getMaxAttachments());
            copy.setFeatures(new ArrayList<>(source.getFeatures()));
            copy.setFeatureValues(new HashMap<>(source.getFeatureValues()));
            copy.setTrialDays(source.getTrialDays());
            copy.setGraceDays(source.getGraceDays());
            return add(copy, "PLAN_DUPLICATED");
        });
    }

    private PlanResponse add(SubscriptionPlan plan, String action) {
        if (plans.findByCode(plan.getCode()).isPresent()) {
            throw new ConflictException("A plan with this code already exists.");
        }
        plans.saveAndFlush(plan);
        remember(plan, null);
        audit.record(action, "SubscriptionPlan", plan.getId(), null, Map.of("code", plan.getCode()));
        return SubscriptionService.toResponse(plan);
    }

    public PlanResponse setActive(UUID id, PlanActiveRequest request) {
        return tx.execute(status -> {
            SubscriptionPlan plan = find(id);
            requireCurrent(plan, request.version());
            boolean before = plan.isActive();
            plan.setActive(request.active());
            plans.saveAndFlush(plan);
            remember(plan, request.reason());
            audit.record(request.active() ? "PLAN_ACTIVATED" : "PLAN_DEACTIVATED", "SubscriptionPlan", plan.getId(),
                    Map.of("active", before), Map.of("active", request.active(), "reason", request.reason()));
            return SubscriptionService.toResponse(plan);
        });
    }

    /** A plan someone is on stays, because its limits still apply to them. Move them to another plan first. */
    public PlanResponse archive(UUID id, PlanActionRequest request) {
        return tx.execute(status -> {
            SubscriptionPlan plan = find(id);
            requireCurrent(plan, request.version());
            if (SubscriptionPlan.TRIAL_CODE.equals(plan.getCode())) {
                throw new BusinessRuleException("New organisations start their trial on this plan, so it must stay.");
            }
            long subscribers = subscribers(id);
            if (subscribers > 0) {
                throw new BusinessRuleException(
                        "This plan has " + subscribers + " subscribers. Move them to another plan first.");
            }
            plan.setActive(false);
            plan.setArchivedAt(Instant.now());
            plans.saveAndFlush(plan);
            remember(plan, request.reason());
            audit.record("PLAN_ARCHIVED", "SubscriptionPlan", plan.getId(), null, Map.of("reason", request.reason()));
            return SubscriptionService.toResponse(plan);
        });
    }

    public PlanImpact impact(UUID id) {
        return tx.execute(status -> {
            find(id);
            return new PlanImpact(subscribers(id));
        });
    }

    public List<PlanVersion> versions(UUID id) {
        return tx.execute(status -> {
            find(id);
            return jdbc.query("""
                    SELECT version, snapshot::text, reason, changed_by, changed_at
                    FROM subscription_plan_versions WHERE plan_id = ? ORDER BY version DESC
                    """, (rows, row) -> new PlanVersion(rows.getLong(1),
                    json.readValue(rows.getString(2), new TypeReference<Map<String, Object>>() { }),
                    rows.getString(3), rows.getObject(4, UUID.class), rows.getTimestamp(5).toInstant()), id);
        });
    }

    SubscriptionPlan find(UUID id) {
        return plans.findById(id).orElseThrow(() -> new ResourceNotFoundException("Plan", id.toString()));
    }

    void requireCurrent(SubscriptionPlan plan, long version) {
        if (plan.getVersion() != version) {
            throw new ConflictException("Someone else has changed this plan. Reload and try again.");
        }
        if (plan.getArchivedAt() != null) {
            throw new ConflictException("This plan is archived.");
        }
    }

    void requireReasonWhenInUse(SubscriptionPlan plan, String reason) {
        if ((reason == null || reason.isBlank()) && subscribers(plan.getId()) > 0) {
            throw new BusinessRuleException("This plan has subscribers, so say why it is changing.");
        }
    }

    /** A save that changed nothing has no new version, so it adds nothing to the history. */
    @Transactional(propagation = Propagation.MANDATORY)
    void remember(SubscriptionPlan plan, String reason) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("name", plan.getName());
        snapshot.put("description", plan.getDescription());
        snapshot.put("monthlyPrice", plan.getMonthlyPrice());
        snapshot.put("annualPrice", plan.getAnnualPrice());
        snapshot.put("taxPercent", plan.getTaxPercent());
        snapshot.put("maxUsers", plan.getMaxUsers());
        snapshot.put("maxFacilities", plan.getMaxFacilities());
        snapshot.put("maxStorageMb", plan.getMaxStorageMb());
        snapshot.put("maxMonthlyInspections", plan.getMaxMonthlyInspections());
        snapshot.put("maxAttachments", plan.getMaxAttachments());
        snapshot.put("features", plan.getFeatures());
        snapshot.put("featureValues", plan.getFeatureValues());
        snapshot.put("trialDays", plan.getTrialDays());
        snapshot.put("graceDays", plan.getGraceDays());
        snapshot.put("active", plan.isActive());
        snapshot.put("publicVisible", plan.isPublicVisible());
        jdbc.update("""
                INSERT INTO subscription_plan_versions (id, plan_id, version, snapshot, reason, changed_by)
                VALUES (?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (plan_id, version) DO NOTHING
                """, UuidV7.generate(), plan.getId(), plan.getVersion(), json.writeValueAsString(snapshot), reason,
                TenantContext.getCurrentUserId());
    }

    private long subscribers(UUID id) {
        return jdbc.queryForObject("SELECT platform_subscriber_count(?)", Long.class, id);
    }
}
