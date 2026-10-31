package com.qualityos.subscription.service;

import com.qualityos.common.audit.AuditService;
import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.subscription.dto.SubscriptionDtos.UsageHistory;
import com.qualityos.subscription.dto.SubscriptionDtos.UsageList;
import com.qualityos.subscription.dto.SubscriptionDtos.UsageRow;
import com.qualityos.subscription.entity.SubscriptionPlan;
import com.qualityos.subscription.repository.SubscriptionPlanRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/** What each organisation uses against its plan. The counts match what {@link PlanLimits} enforces. */
@Service
public class PlatformUsage {

    private static final int MAX_EXPORT = 5000;
    private static final double NEAR = 0.8;
    private static final long MEGABYTE = 1_048_576L;
    private static final Set<String> ALERTS = Set.of("NEAR", "REACHED");
    private static final String PAGE = "SELECT * FROM platform_usage_page(?, ?, ?, ?)";
    private static final String HISTORY = "SELECT * FROM platform_usage_history(?, ?)";

    private final JdbcTemplate jdbc;
    private final SubscriptionPlanRepository plans;
    private final AuditService audit;
    private final TransactionTemplate tx;

    PlatformUsage(
            JdbcTemplate jdbc,
            SubscriptionPlanRepository plans,
            AuditService audit,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public UsageList page(String q, String alert, int page, int size) {
        List<Long> total = new ArrayList<>();
        List<UsageRow> rows = rows(q, alert, size, page * size, total);
        return new UsageList(total.isEmpty() ? 0 : total.getFirst().intValue(), rows);
    }

    /** Leaves a trace, because the file shows every organisation's activity. */
    public List<UsageRow> export(String q, String alert) {
        List<UsageRow> rows = rows(q, alert, MAX_EXPORT, 0, new ArrayList<>());
        tx.executeWithoutResult(status -> audit.record(
                "USAGE_EXPORTED", "Organisation", null, null, Map.of("rows", rows.size())));
        return rows;
    }

    public List<UsageHistory> history(UUID tenant, int days) {
        return tx.execute(status -> jdbc.query(HISTORY, (rows, row) -> new UsageHistory(
                rows.getObject("taken_on", java.time.LocalDate.class), rows.getLong("users"), rows.getLong("facilities"),
                rows.getLong("storage_bytes"), rows.getLong("inspections"), rows.getLong("attachments")), tenant, days));
    }

    private List<UsageRow> rows(String q, String alert, int limit, int offset, List<Long> total) {
        if (alert != null && !ALERTS.contains(alert)) {
            throw new BusinessRuleException("Unknown alert.");
        }
        return tx.execute(status -> {
            List<SubscriptionPlan> offered = plans.findByActiveTrueOrderByDisplayOrder();
            return jdbc.query(PAGE, (rows, row) -> {
                total.clear();
                total.add(rows.getLong("total"));
                return toRow(rows, offered);
            }, StringUtils.hasText(q) ? q.trim() : null, alert, limit, offset);
        });
    }

    private static UsageRow toRow(ResultSet rows, List<SubscriptionPlan> offered) throws SQLException {
        double highest = rows.getDouble("highest");
        String level = highest >= 1 ? "REACHED" : highest >= NEAR ? "NEAR" : "OK";
        long users = rows.getLong("users");
        long facilities = rows.getLong("facilities");
        long storage = rows.getLong("storage_bytes");
        long inspections = rows.getLong("inspections");
        long attachments = rows.getLong("attachments");
        String features = rows.getString("features");
        int order = rows.getInt("display_order");
        String upgrade = highest < NEAR ? null : offered.stream()
                .filter(plan -> plan.getDisplayOrder() > order)
                .filter(plan -> room(plan.getMaxUsers(), users) && room(plan.getMaxFacilities(), facilities)
                        && room(plan.getMaxMonthlyInspections(), inspections) && room(plan.getMaxAttachments(), attachments)
                        && (plan.getMaxStorageMb() == null || plan.getMaxStorageMb() * MEGABYTE > storage))
                .map(SubscriptionPlan::getName)
                .findFirst()
                .orElse(null);
        return new UsageRow(rows.getObject("tenant_id", UUID.class), rows.getString("name"), rows.getString("plan_name"),
                features == null || features.isEmpty() ? List.of() : List.of(features.split(",")),
                users, rows.getObject("max_users", Integer.class), facilities, rows.getObject("max_facilities", Integer.class),
                storage, rows.getObject("max_storage_mb", Integer.class), inspections,
                rows.getObject("max_monthly_inspections", Integer.class), attachments,
                rows.getObject("max_attachments", Integer.class), level, upgrade);
    }


    private static boolean room(Integer limit, long used) {
        return limit == null || limit > used;
    }
}
