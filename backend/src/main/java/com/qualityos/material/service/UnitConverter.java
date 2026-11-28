package com.qualityos.material.service;

import com.qualityos.material.entity.ItemUnitConversion;
import com.qualityos.material.entity.UnitOfMeasure;
import com.qualityos.material.repository.ItemUnitConversionRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Works out a quantity in another unit, exactly. Units of one kind convert by their shared factors (GM to KG) for
 * every item. An item's own rules add the rest, through its stock unit: for glycerin kept in KG, 1 LTR = 1.26 KG
 * lets 500 ML become 0.63 KG, and 1 DRUM = 200 KG lets 4 DRUM become 800 KG. A pack with no size of its own, such
 * as a carton, is sized by each item's rule: 12 vials for one product, 48 for another.
 */
@Component
public class UnitConverter {

    /** An item whose quantities are converted: its code, for messages, and the unit its stock is kept in. */
    public record Item(UUID id, String code, UUID stockUnitId) {
    }

    private final ItemUnitConversionRepository rules;
    private final JdbcTemplate jdbc;

    UnitConverter(ItemUnitConversionRepository rules, JdbcTemplate jdbc) {
        this.rules = rules;
        this.jdbc = jdbc;
    }

    /** The material or product with this id, or empty when it is neither. */
    public Optional<Item> item(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                        SELECT id, code, uom_id FROM materials WHERE id = ?
                        UNION ALL SELECT id, code, uom_id FROM products WHERE id = ?
                        """,
                (row, n) -> new Item(row.getObject("id", UUID.class), row.getString("code"),
                        row.getObject("uom_id", UUID.class)), id, id).stream().findFirst();
    }

    public List<ItemUnitConversion> rulesOf(UUID itemId) {
        return itemId == null ? List.of() : rules.findByItemIdOrderByCreatedAt(itemId);
    }

    /**
     * The quantity in {@code to}, unrounded, or empty when nothing converts one unit to the other for this item.
     *
     * @param units every unit the organisation can see, by id
     * @param item  the material or product the quantity is of, or null for shared factors alone
     */
    Optional<BigDecimal> exact(BigDecimal quantity, UnitOfMeasure from, UnitOfMeasure to, Item item,
            Map<UUID, UnitOfMeasure> units) {
        if (from.getId().equals(to.getId())) {
            return Optional.of(quantity);
        }
        Map<UUID, ItemUnitConversion> own = item == null ? Map.of() : rulesOf(item.id()).stream()
                .collect(Collectors.toMap(ItemUnitConversion::getUomId, Function.identity()));
        if (!own.containsKey(from.getId()) && !own.containsKey(to.getId())) {
            Optional<BigDecimal> shared = shared(quantity, from, to);
            if (shared.isPresent() || item == null) {
                return shared;
            }
        }
        if (item == null || item.stockUnitId() == null) {
            return Optional.empty();
        }
        UnitOfMeasure stock = units.get(item.stockUnitId());
        return toStock(quantity, from, stock, own, units).flatMap(inStock -> fromStock(inStock, to, stock, own, units));
    }

    private Optional<BigDecimal> toStock(BigDecimal quantity, UnitOfMeasure from, UnitOfMeasure stock,
            Map<UUID, ItemUnitConversion> own, Map<UUID, UnitOfMeasure> units) {
        if (from.getId().equals(stock.getId())) {
            return Optional.of(quantity);
        }
        ItemUnitConversion exact = own.get(from.getId());
        if (exact != null) {
            return Optional.of(quantity.multiply(exact.getFactor()));
        }
        Optional<BigDecimal> shared = shared(quantity, from, stock);
        if (shared.isPresent()) {
            return shared;
        }
        return sameKindRule(from, own, units).flatMap(rule -> shared(quantity, from, units.get(rule.getUomId()))
                .map(inRuleUnit -> inRuleUnit.multiply(rule.getFactor())));
    }

    private Optional<BigDecimal> fromStock(BigDecimal quantity, UnitOfMeasure to, UnitOfMeasure stock,
            Map<UUID, ItemUnitConversion> own, Map<UUID, UnitOfMeasure> units) {
        if (to.getId().equals(stock.getId())) {
            return Optional.of(quantity);
        }
        ItemUnitConversion exact = own.get(to.getId());
        if (exact != null) {
            return Optional.of(quantity.divide(exact.getFactor(), MathContext.DECIMAL128));
        }
        Optional<BigDecimal> shared = shared(quantity, stock, to);
        if (shared.isPresent()) {
            return shared;
        }
        return sameKindRule(to, own, units).flatMap(rule -> shared(
                quantity.divide(rule.getFactor(), MathContext.DECIMAL128), units.get(rule.getUomId()), to));
    }

    /** The item's rule for a unit that converts with {@code unit} by shared factors, such as LTR for ML. */
    private static Optional<ItemUnitConversion> sameKindRule(UnitOfMeasure unit, Map<UUID, ItemUnitConversion> own,
            Map<UUID, UnitOfMeasure> units) {
        return own.values().stream().filter(rule -> convertible(units.get(rule.getUomId()), unit)).findFirst();
    }

    static boolean convertible(UnitOfMeasure one, UnitOfMeasure other) {
        return one != null && other != null && one.getToBase() != null && other.getToBase() != null
                && one.getDimension().equalsIgnoreCase(other.getDimension());
    }

    private static Optional<BigDecimal> shared(BigDecimal quantity, UnitOfMeasure from, UnitOfMeasure to) {
        if (from.getId().equals(to.getId())) {
            return Optional.of(quantity);
        }
        if (!convertible(from, to)) {
            return Optional.empty();
        }
        return Optional.of(quantity.multiply(from.getToBase()).divide(to.getToBase(), MathContext.DECIMAL128));
    }
}
