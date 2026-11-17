package com.qualityos.inspection.entity;

import com.qualityos.common.exception.BusinessRuleException;
import java.math.BigDecimal;
import java.util.List;

/**
 * Single sampling plans for normal inspection, as in ISO 2859-1 (ANSI/ASQ Z1.4, MIL-STD-105E): Table 1 gives a code
 * letter from the lot size and general inspection level, and Table 2-A the sample size and acceptance number for that
 * letter and the AQL. Tightened and reduced inspection, switching rules and double or multiple plans are not covered.
 *
 * <p>In Table 2-A an acceptance number depends only on how far the letter and the AQL column sit along the table's
 * diagonal, so each AQL column is the same sequence shifted by one letter: an arrow down to the first plan with
 * acceptance 0, the 0, an arrow up, an arrow down, then 1, 2, 3, 5, 7, 10, 14, 21, and arrows up after that. An arrow
 * means the neighbouring plan is used, its sample size included.
 */
public final class AqlTable {

    /** The general inspection levels. Level II is the usual one. */
    public enum Level { I, II, III }

    /** @param codeLetter the letter of the plan used, after following any arrow */
    public record Plan(char codeLetter, int sampleSize, int acceptanceNumber) {
    }

    /** The AQL columns this table offers, in percent nonconforming. */
    public static final List<BigDecimal> AQLS = List.of("0.010", "0.015", "0.025", "0.040", "0.065", "0.10", "0.15",
            "0.25", "0.40", "0.65", "1.0", "1.5", "2.5", "4.0", "6.5", "10").stream().map(BigDecimal::new).toList();

    private static final char[] LETTERS = "ABCDEFGHJKLMNPQR".toCharArray();
    private static final int[] SAMPLE_SIZES = {2, 3, 5, 8, 13, 20, 32, 50, 80, 125, 200, 315, 500, 800, 1250, 2000};

    /** Upper lot size of each row of Table 1, and the letter index each general level gives it. */
    private static final long[] LOT_LIMITS = {8, 15, 25, 50, 90, 150, 280, 500, 1200, 3200, 10_000, 35_000, 150_000,
            500_000, Long.MAX_VALUE};
    private static final int[][] LEVEL_LETTERS = {
            // I, II, III
            {0, 0, 1}, {0, 1, 2}, {1, 2, 3}, {2, 3, 4}, {2, 4, 5}, {3, 5, 6}, {4, 6, 7}, {5, 7, 8}, {6, 8, 9},
            {7, 9, 10}, {8, 10, 11}, {9, 11, 12}, {10, 12, 13}, {11, 13, 14}, {12, 14, 15}};

    /** The acceptance numbers along the diagonal, from where a column's first plan accepts nothing. */
    private static final int ZERO = 14;
    private static final int[] ACCEPTANCE = {0, -1, -1, 1, 2, 3, 5, 7, 10, 14, 21};

    private AqlTable() {
    }

    /** @return the letter index from Table 1. A lot of fewer than two units counts as the smallest row. */
    static int letterFor(long lotSize, Level level) {
        int row = 0;
        while (lotSize > LOT_LIMITS[row]) {
            row++;
        }
        return LEVEL_LETTERS[row][level.ordinal()];
    }

    /**
     * The plan for a lot. When the sample size is at least the lot size, every unit is inspected, with the plan's
     * acceptance number.
     */
    public static Plan plan(long lotSize, Level level, BigDecimal aql) {
        int column = column(aql);
        int letter = letterFor(lotSize, level);
        int step = letter + column - ZERO;
        if (step < 0) {
            letter -= step; // arrow down to the first plan accepting nothing
        } else if (step == 1) {
            letter -= 1; // arrow up to the plan accepting nothing
        } else if (step == 2) {
            letter += 1; // arrow down to the plan accepting one
        } else if (step >= ACCEPTANCE.length) {
            letter -= step - (ACCEPTANCE.length - 1); // arrow up to the plan accepting 21
        }
        int acceptance = ACCEPTANCE[letter + column - ZERO];
        int sampleSize = (int) Math.min(SAMPLE_SIZES[letter], Math.max(lotSize, 1));
        return new Plan(LETTERS[letter], sampleSize, acceptance);
    }

    /** @return the AQL's column, refusing a value the table does not have */
    public static int column(BigDecimal aql) {
        for (int index = 0; index < AQLS.size(); index++) {
            if (AQLS.get(index).compareTo(aql) == 0) {
                return index;
            }
        }
        throw new BusinessRuleException("Choose an AQL from the standard table, between 0.010 and 10.");
    }
}
