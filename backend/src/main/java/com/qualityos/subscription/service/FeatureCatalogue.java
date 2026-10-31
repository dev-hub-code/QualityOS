package com.qualityos.subscription.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.common.exception.ConflictException;
import com.qualityos.common.exception.ResourceNotFoundException;
import com.qualityos.subscription.dto.SubscriptionDtos.AssignFeatureRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.CreateFeatureRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.FeatureActiveRequest;
import com.qualityos.subscription.dto.SubscriptionDtos.FeatureOrganisation;
import com.qualityos.subscription.dto.SubscriptionDtos.FeatureOrganisationList;
import com.qualityos.subscription.dto.SubscriptionDtos.FeatureResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.PlanResponse;
import com.qualityos.subscription.dto.SubscriptionDtos.UpdateFeatureRequest;
import com.qualityos.subscription.entity.FeatureDefinition;
import com.qualityos.subscription.entity.SubscriptionPlan;
import com.qualityos.subscription.repository.FeatureDefinitionRepository;
import com.qualityos.subscription.repository.SubscriptionPlanRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What plans can include. A feature only restricts anything once {@link PlanAccess} knows it. Until then it is a
 * record of what a plan offers, and the catalogue says so rather than implying it is enforced.
 */
@Service
public class FeatureCatalogue {

    private final FeatureDefinitionRepository features;
    private final SubscriptionPlanRepository plans;
    private final PlanCatalogue catalogue;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    FeatureCatalogue(
            FeatureDefinitionRepository features,
            SubscriptionPlanRepository plans,
            PlanCatalogue catalogue,
            AuditService audit,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.features = features;
        this.plans = plans;
        this.catalogue = catalogue;
        this.audit = audit;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public List<FeatureResponse> list() {
        return tx.execute(status -> {
            Map<String, Long> counts = new HashMap<>();
            jdbc.query("SELECT code, organisations FROM platform_feature_counts()",
                    rows -> { counts.put(rows.getString("code"), rows.getLong("organisations")); });
            return features.findAllByOrderByName().stream()
                    .map(feature -> toResponse(feature, counts.getOrDefault(feature.getCode(), 0L)))
                    .toList();
        });
    }

    public FeatureResponse create(CreateFeatureRequest request) {
        return tx.execute(status -> {
            if (features.findByCode(request.code()).isPresent()) {
                throw new ConflictException("A feature with this code already exists.");
            }
            FeatureDefinition feature = new FeatureDefinition(request.code(), request.name().trim(), request.valueType());
            feature.setDescription(request.description() == null ? "" : request.description().trim());
            feature.setDefaultEnabled(request.defaultEnabled());
            features.saveAndFlush(feature);
            audit.record("FEATURE_CREATED", "FeatureDefinition", feature.getId(), null, Map.of("code", feature.getCode()));
            return toResponse(feature, 0);
        });
    }

    public FeatureResponse update(String code, UpdateFeatureRequest request) {
        return tx.execute(status -> {
            FeatureDefinition feature = find(code);
            feature.requireVersion(request.version());
            feature.setName(request.name().trim());
            feature.setDescription(request.description() == null ? "" : request.description().trim());
            feature.setDefaultEnabled(request.defaultEnabled());
            features.saveAndFlush(feature);
            audit.record("FEATURE_UPDATED", "FeatureDefinition", feature.getId(), null, Map.of("code", code));
            return toResponse(feature, 0);
        });
    }

    /** An inactive feature cannot be added to another plan. Plans that have it keep it, and nothing unlocks. */
    public FeatureResponse setActive(String code, FeatureActiveRequest request) {
        return tx.execute(status -> {
            FeatureDefinition feature = find(code);
            feature.requireVersion(request.version());
            feature.setActive(request.active());
            features.saveAndFlush(feature);
            audit.record(request.active() ? "FEATURE_ACTIVATED" : "FEATURE_DEACTIVATED", "FeatureDefinition",
                    feature.getId(), null, Map.of("code", code, "reason", request.reason()));
            return toResponse(feature, 0);
        });
    }

    public PlanResponse assign(String code, AssignFeatureRequest request) {
        return tx.execute(status -> {
            FeatureDefinition feature = find(code);
            SubscriptionPlan plan = plans.findById(request.planId())
                    .orElseThrow(() -> new ResourceNotFoundException("Plan", request.planId().toString()));
            catalogue.requireCurrent(plan, request.version());
            catalogue.requireReasonWhenInUse(plan, request.reason());
            List<String> included = new ArrayList<>(plan.getFeatures());
            Map<String, Object> values = new HashMap<>(plan.getFeatureValues());
            included.remove(code);
            values.remove(code);
            if (request.included()) {
                if (!feature.isActive() && !plan.getFeatures().contains(code)) {
                    throw new BusinessRuleException("This feature is not active, so it cannot be added to a plan.");
                }
                included.add(code);
                requireValue(feature, request.value());
                if (request.value() != null) {
                    values.put(code, request.value());
                }
            }
            plan.setFeatures(included);
            plan.setFeatureValues(values);
            plans.saveAndFlush(plan);
            catalogue.remember(plan, request.reason());
            Map<String, Object> change = new HashMap<>();
            change.put("feature", code);
            change.put("plan", plan.getCode());
            change.put("included", request.included());
            change.put("reason", request.reason() == null ? "" : request.reason());
            audit.record("PLAN_FEATURE_CHANGED", "SubscriptionPlan", plan.getId(), null, change);
            return SubscriptionService.toResponse(plan);
        });
    }

    public FeatureOrganisationList organisations(String code, int page, int size) {
        return tx.execute(status -> {
            find(code);
            List<Long> total = new ArrayList<>();
            List<FeatureOrganisation> rows = jdbc.query(
                    "SELECT * FROM platform_feature_organisations(?, ?, ?)",
                    (row, index) -> {
                        total.clear();
                        total.add(row.getLong("total"));
                        return new FeatureOrganisation(row.getObject("tenant_id", UUID.class), row.getString("name"),
                                row.getString("plan_name"));
                    }, code, size, page * size);
            return new FeatureOrganisationList(total.isEmpty() ? 0 : total.getFirst().intValue(), rows);
        });
    }

    /** With a number, blank means unlimited. Text and structured values mean nothing when blank. */
    private static void requireValue(FeatureDefinition feature, Object value) {
        boolean fits = switch (feature.getValueType()) {
            case BOOLEAN -> value == null || value instanceof Boolean;
            case NUMBER -> value == null || value instanceof Number;
            case TEXT -> value instanceof String text && !text.isBlank();
            case STRUCTURED -> value instanceof Map<?, ?> || value instanceof List<?>;
        };
        if (!fits) {
            throw new BusinessRuleException("This feature needs a value of type " + feature.getValueType() + ".");
        }
    }

    private FeatureDefinition find(String code) {
        return features.findByCode(code).orElseThrow(() -> new ResourceNotFoundException("Feature", code));
    }

    private static FeatureResponse toResponse(FeatureDefinition feature, long organisations) {
        return new FeatureResponse(feature.getCode(), feature.getName(), feature.getDescription(), feature.getValueType(),
                feature.isActive(), feature.isDefaultEnabled(), PlanAccess.FEATURES.containsKey(feature.getCode()),
                organisations, feature.getVersion());
    }
}
