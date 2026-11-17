package com.qualityos.production.service;

import com.qualityos.common.archive.ArchiveGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps products, materials and facilities that production still relies on from being archived. Row-level security
 * limits every count to the current organisation. Counts cover every facility, since archiving is organisation-wide.
 */
@Component
class ProductionArchiveGuard implements ArchiveGuard {

    private static final String OPEN_ORDER =
            "status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'RELEASED', 'IN_PROGRESS', 'ON_HOLD')";
    private static final String IN_EFFECT =
            "status = 'APPROVED' AND (effective_to IS NULL OR effective_to >= current_date)";
    private static final String OPEN_PLAN = "status IN ('DRAFT', 'APPROVED')";
    private static final String BOM_IN_EFFECT =
            "b.status = 'APPROVED' AND (b.effective_to IS NULL OR b.effective_to >= current_date)";

    private final JdbcTemplate jdbc;

    ProductionArchiveGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> usesOf(Kind kind, UUID id) {
        List<String> uses = new ArrayList<>();
        switch (kind) {
            case PRODUCT -> {
                add(uses, "open work order", "open work orders",
                        "SELECT count(*) FROM work_orders WHERE product_id = ? AND " + OPEN_ORDER, id);
                add(uses, "approved BOM or recipe", "approved BOMs or recipes",
                        "SELECT count(*) FROM boms WHERE product_id = ? AND " + IN_EFFECT, id);
                add(uses, "approved routing", "approved routings",
                        "SELECT count(*) FROM routings WHERE product_id = ? AND " + IN_EFFECT, id);
                add(uses, "approved BOM or recipe that uses it as a component",
                        "approved BOMs or recipes that use it as a component", """
                        SELECT count(DISTINCT b.id) FROM bom_lines l
                        JOIN boms b ON b.tenant_id = l.tenant_id AND b.id = l.bom_id
                        WHERE l.component_product_id = ? AND \s""" + BOM_IN_EFFECT, id);
            }
            case MATERIAL -> {
                add(uses, "approved BOM or recipe", "approved BOMs or recipes", """
                        SELECT count(DISTINCT b.id) FROM bom_lines l
                        JOIN boms b ON b.tenant_id = l.tenant_id AND b.id = l.bom_id
                        WHERE (l.material_id = ? OR l.alternate_material_ids ?? ?::text) AND \s"""
                        + BOM_IN_EFFECT, id, id);
                add(uses, "open work order", "open work orders", """
                        SELECT count(DISTINCT w.id) FROM work_order_materials m
                        JOIN work_orders w ON w.tenant_id = m.tenant_id AND w.id = m.work_order_id
                        WHERE (m.material_id = ? OR m.alternate_material_ids ?? ?::text) AND w.""" + OPEN_ORDER,
                        id, id);
                add(uses, "open reservation of its lots", "open reservations of its lots", """
                        SELECT count(*) FROM material_reservations r
                        JOIN work_order_materials m ON m.tenant_id = r.tenant_id AND m.id = r.work_order_material_id
                        WHERE m.material_id = ? AND r.open_quantity > 0""", id);
            }
            case FACILITY -> {
                add(uses, "work centre in use", "work centres in use",
                        "SELECT count(*) FROM work_centers WHERE facility_id = ? AND archived_at IS NULL", id);
                add(uses, "open work order", "open work orders",
                        "SELECT count(*) FROM work_orders WHERE facility_id = ? AND " + OPEN_ORDER, id);
                add(uses, "open production plan", "open production plans",
                        "SELECT count(*) FROM production_plans WHERE facility_id = ? AND " + OPEN_PLAN, id);
            }
        }
        return uses;
    }

    private void add(List<String> uses, String one, String many, String sql, Object... arguments) {
        Long count = jdbc.queryForObject(sql, Long.class, arguments);
        if (count != null && count > 0) {
            uses.add(count + " " + (count == 1 ? one : many));
        }
    }
}
