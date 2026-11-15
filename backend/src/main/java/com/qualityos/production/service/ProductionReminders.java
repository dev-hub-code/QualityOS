package com.qualityos.production.service;

import com.qualityos.organization.service.OrganizationService;
import com.qualityos.product.service.ProductService;
import com.qualityos.production.entity.ProductionPlan;
import com.qualityos.production.entity.ProductionPlanLine;
import com.qualityos.production.entity.WorkOrder;
import com.qualityos.production.repository.ProductionPlanLineRepository;
import com.qualityos.production.repository.ProductionPlanRepository;
import com.qualityos.production.repository.WorkOrderRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chases late production, once a week until it is done: work orders past their planned end that are not finished,
 * and lines of an open plan past their due date with less made than planned. Runs under the current tenant.
 */
@Service
public class ProductionReminders {

    static final Duration INTERVAL = Duration.ofDays(7);

    /** Not finished, and either approved to run or running. A draft or submitted order is not yet a promise. */
    private static final String LATE_ORDERS = """
            SELECT id FROM work_orders
            WHERE status IN ('APPROVED', 'RELEASED', 'IN_PROGRESS', 'ON_HOLD') AND planned_end < ?
              AND (overdue_notified_at IS NULL OR overdue_notified_at < ?)
            ORDER BY planned_end, work_order_number
            """;

    /** Made means received into finished goods by the line's work orders. */
    private static final String LATE_LINES = """
            SELECT l.id, COALESCE(made.quantity, 0) AS made
            FROM production_plan_lines l
            JOIN production_plans p ON p.tenant_id = l.tenant_id AND p.id = l.plan_id
            LEFT JOIN LATERAL (
                SELECT sum(r.quantity) AS quantity FROM finished_goods_receipts r
                JOIN work_orders w ON w.tenant_id = r.tenant_id AND w.id = r.work_order_id
                WHERE w.plan_line_id = l.id
            ) made ON true
            WHERE p.status = 'APPROVED' AND l.due_date < ?
              AND (l.overdue_notified_at IS NULL OR l.overdue_notified_at < ?)
              AND COALESCE(made.quantity, 0) < l.quantity
            ORDER BY l.due_date
            """;

    private final WorkOrderRepository workOrders;
    private final ProductionPlanRepository plans;
    private final ProductionPlanLineRepository lines;
    private final ProductService products;
    private final OrganizationService organizations;
    private final ProductionAlerts alerts;
    private final JdbcTemplate jdbc;

    ProductionReminders(
            WorkOrderRepository workOrders,
            ProductionPlanRepository plans,
            ProductionPlanLineRepository lines,
            ProductService products,
            OrganizationService organizations,
            ProductionAlerts alerts,
            JdbcTemplate jdbc) {
        this.workOrders = workOrders;
        this.plans = plans;
        this.lines = lines;
        this.products = products;
        this.organizations = organizations;
        this.alerts = alerts;
        this.jdbc = jdbc;
    }

    /** @return how many work orders and plan lines were chased */
    @Transactional
    public int remindOverdue() {
        LocalDate today = LocalDate.now(ZoneId.of(organizations.getCurrent().timezone()));
        Instant now = Instant.now();
        Timestamp before = Timestamp.from(now.minus(INTERVAL));
        int sent = 0;
        for (UUID id : jdbc.queryForList(LATE_ORDERS, UUID.class, today, before)) {
            WorkOrder order = workOrders.findById(id).orElseThrow();
            alerts.workOrderOverdue(order, code(order.getProductId()));
            order.setOverdueNotifiedAt(now);
            sent++;
        }
        List<LateLine> late = jdbc.query(LATE_LINES, (rs, row) ->
                new LateLine(rs.getObject("id", UUID.class), rs.getBigDecimal("made")), today, before);
        for (LateLine found : late) {
            ProductionPlanLine line = lines.findById(found.id()).orElseThrow();
            ProductionPlan plan = plans.findById(line.getPlanId()).orElseThrow();
            alerts.planLineOverdue(plan, code(line.getProductId()), line.getQuantity(), found.made(), line.getDueDate());
            line.setOverdueNotifiedAt(now);
            sent++;
        }
        return sent;
    }

    private String code(UUID productId) {
        return products.references(List.of(productId)).get(productId).code();
    }

    private record LateLine(UUID id, BigDecimal made) {
    }
}
