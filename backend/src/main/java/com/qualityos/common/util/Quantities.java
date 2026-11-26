package com.qualityos.common.util;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Quantities of different units are shown side by side, never added together. */
public final class Quantities {

    private Quantities() {
    }

    /** {@code 100.5 M}, or the number alone when there is no unit. */
    public static String label(BigDecimal quantity, String unit) {
        String number = quantity.stripTrailingZeros().toPlainString();
        return unit == null || unit.isBlank() ? number : number + " " + unit;
    }

    /** Totals by unit, in the order units first appear: {@code 100.5 M · 5 PCS}, or {@code 0} for nothing. */
    public static <T> String total(Collection<T> rows, Function<T, String> unit, Function<T, BigDecimal> quantity) {
        Map<String, BigDecimal> totals = rows.stream().collect(Collectors.toMap(
                row -> Objects.requireNonNullElse(unit.apply(row), ""), quantity, BigDecimal::add, LinkedHashMap::new));
        if (totals.isEmpty()) {
            return "0";
        }
        return totals.entrySet().stream().map(entry -> label(entry.getValue(), entry.getKey()))
                .collect(Collectors.joining(" · "));
    }

    /** Whether the rows are all in one unit, so that their quantities may be added. */
    public static <T> boolean oneUnit(Collection<T> rows, Function<T, String> unit) {
        return rows.stream().map(row -> Objects.requireNonNullElse(unit.apply(row), "")).distinct().count() <= 1;
    }
}
