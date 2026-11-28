package com.qualityos.material.service;

import com.qualityos.common.exception.BusinessRuleException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps a material's or product's unit fixed once a quantity has been recorded in it. Batches, dispatches, reports and
 * recalls read their unit from the item, so changing it would relabel history: 985 LTR would become 985 KG.
 */
@Component
public class UnitChangeGuard {

    private record Use(String what, String sql) {
    }

    private static final Use CONVERSIONS = new Use("unit conversions of its own",
            "SELECT EXISTS (SELECT 1 FROM item_unit_conversions WHERE item_id = ?)");

    private static final List<Use> MATERIAL = List.of(CONVERSIONS,
            new Use("purchase orders", "SELECT EXISTS (SELECT 1 FROM purchase_order_items WHERE material_id = ?)"),
            new Use("goods receipts", "SELECT EXISTS (SELECT 1 FROM material_receipt_items WHERE material_id = ?)"),
            new Use("BOMs or recipes", "SELECT EXISTS (SELECT 1 FROM bom_lines WHERE material_id = ?)"),
            new Use("product material lists", "SELECT EXISTS (SELECT 1 FROM product_materials WHERE material_id = ?)"),
            new Use("work orders", "SELECT EXISTS (SELECT 1 FROM work_order_materials WHERE material_id = ?)"));

    private static final List<Use> PRODUCT = List.of(CONVERSIONS,
            new Use("production batches", "SELECT EXISTS (SELECT 1 FROM production_batches WHERE product_id = ?)"),
            new Use("work orders", "SELECT EXISTS (SELECT 1 FROM work_orders WHERE product_id = ?)"
                    + " OR EXISTS (SELECT 1 FROM work_order_materials WHERE component_product_id = ?)"),
            new Use("BOMs or recipes", "SELECT EXISTS (SELECT 1 FROM boms WHERE product_id = ?)"
                    + " OR EXISTS (SELECT 1 FROM bom_lines WHERE component_product_id = ?)"),
            new Use("production plans", "SELECT EXISTS (SELECT 1 FROM production_plan_lines WHERE product_id = ?)"),
            new Use("its material list", "SELECT EXISTS (SELECT 1 FROM product_materials WHERE product_id = ?)"));

    private final JdbcTemplate jdbc;

    UnitChangeGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws BusinessRuleException when the unit changes and a quantity is already recorded in the old one */
    public void requireMaterialUnitFree(String code, String oldUnit, UUID id, UUID from, UUID to) {
        require(MATERIAL, "material", code, oldUnit, id, from, to);
    }

    /** @throws BusinessRuleException when the unit changes and a quantity is already recorded in the old one */
    public void requireProductUnitFree(String code, String oldUnit, UUID id, UUID from, UUID to) {
        require(PRODUCT, "product", code, oldUnit, id, from, to);
    }

    /** Where the item's quantities are entered in one of these units, such as "BOMs or recipes"; null when nowhere. */
    public String useOfUnits(UUID itemId, List<UUID> unitIds) {
        if (unitIds.isEmpty()) {
            return null;
        }
        String units = unitIds.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",", "{", "}"));
        List<Use> uses = List.of(
                new Use("BOMs or recipes", "SELECT EXISTS (SELECT 1 FROM bom_lines WHERE (material_id = ? "
                        + "OR component_product_id = ?) AND uom_id = ANY (?::uuid[]))"),
                new Use("material lists", "SELECT EXISTS (SELECT 1 FROM product_materials WHERE material_id = ? "
                        + "AND uom_id = ANY (?::uuid[]))"),
                new Use("work orders", "SELECT EXISTS (SELECT 1 FROM work_order_materials WHERE (material_id = ? "
                        + "OR component_product_id = ?) AND uom_id = ANY (?::uuid[]))"),
                new Use("purchase orders", "SELECT EXISTS (SELECT 1 FROM purchase_order_items WHERE material_id = ? "
                        + "AND entered_uom_id = ANY (?::uuid[]))"));
        for (Use use : uses) {
            long ids = use.sql().chars().filter(c -> c == '?').count() - 1;
            Object[] args = new Object[(int) ids + 1];
            java.util.Arrays.fill(args, itemId);
            args[(int) ids] = units;
            if (Boolean.TRUE.equals(jdbc.queryForObject(use.sql(), Boolean.class, args))) {
                return use.what();
            }
        }
        return null;
    }

    private void require(List<Use> uses, String noun, String code, String oldUnit, UUID id, UUID from, UUID to) {
        if (from == null || from.equals(to)) {
            return;
        }
        for (Use use : uses) {
            Object[] args = use.sql().chars().filter(c -> c == '?').mapToObj(c -> id).toArray();
            if (Boolean.TRUE.equals(jdbc.queryForObject(use.sql(), Boolean.class, args)) && use == CONVERSIONS) {
                throw new BusinessRuleException(("%s has unit conversions to %s, so its unit cannot change. Remove "
                        + "them first.").formatted(code, oldUnit));
            }
            if (Boolean.TRUE.equals(jdbc.queryForObject(use.sql(), Boolean.class, args))) {
                throw new BusinessRuleException(("%s is on %s in %s, so its unit cannot change: the quantities already "
                        + "recorded would be read in the new unit. Make a new %s for the new unit.")
                        .formatted(code, use.what(), oldUnit, noun));
            }
        }
    }
}
