package com.qualityos.subscription.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Narrows a member's permissions to what the subscription allows. It sits where permissions are resolved, so that
 * every endpoint and background check agrees, and a new controller needs no billing logic of its own.
 */
@Component
public class PlanAccess {

    public static final String SUBSCRIPTION_MANAGE = "SUBSCRIPTION_MANAGE";

    /** Feature to the permission prefixes it carries. Anything not listed here comes with every plan. */
    static final Map<String, List<String>> FEATURES = Map.of(
            "NCR_CAPA", List.of("NCR_", "CAPA_"),
            "REPORTS", List.of("REPORT_"),
            "ADVANCED", List.of("TRACEABILITY_", "RECALL_", "CALIBRATION_", "AUDIT_VIEW", "AUDIT_MANAGE", "AUDIT_CLOSE",
                    "MATERIAL_CORRECTION_"));

    private static final String CURRENT = """
            SELECT s.current_period_ends_at, p.grace_days, array_to_string(p.features, ',') AS features
            FROM subscriptions s JOIN subscription_plans p ON p.id = s.plan_id
            WHERE s.tenant_id = ?
            """;

    private final JdbcTemplate jdbc;

    PlanAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Runs under the tenant, in the caller's transaction. With no subscription row, nothing can be changed. */
    public Set<String> narrow(UUID tenantId, Set<String> permissions) {
        return jdbc.query(CURRENT, rows -> {
            if (!rows.next()) {
                return permissions.stream().filter(PlanAccess::isSafeWhenReadOnly).collect(Collectors.toSet());
            }
            Instant endsAt = rows.getTimestamp("current_period_ends_at").toInstant();
            boolean readOnly = SubscriptionState.of(endsAt, rows.getInt("grace_days"), Instant.now())
                    == SubscriptionState.READ_ONLY;
            Set<String> enabled = Set.of(rows.getString("features").split(","));
            boolean frozen = readOnly || maintenance();
            return permissions.stream()
                    .filter(permission -> allowedByPlan(permission, enabled))
                    .filter(permission -> !frozen || isSafeWhenReadOnly(permission))
                    .collect(Collectors.toSet());
        }, tenantId);
    }

    private boolean maintenance() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT maintenance_mode FROM platform_settings", Boolean.class));
    }

    /** Records already made stay readable after a downgrade. Reports are the paid feature itself, so they go. */
    static boolean allowedByPlan(String permission, Set<String> enabled) {
        return FEATURES.entrySet().stream()
                .filter(feature -> !enabled.contains(feature.getKey()))
                .noneMatch(feature -> feature.getValue().stream().anyMatch(permission::startsWith)
                        && (!permission.endsWith("_VIEW") || permission.startsWith("REPORT_")));
    }

    private static boolean isSafeWhenReadOnly(String permission) {
        return permission.endsWith("_VIEW") || permission.equals(SUBSCRIPTION_MANAGE);
    }
}
