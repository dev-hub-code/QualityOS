package com.qualityos.inspection.entity;

import com.qualityos.common.exception.BusinessRuleException;
import java.math.BigDecimal;
import java.util.List;

/**
 * Single sampling plans by attributes, as tabled in ANSI/ASQ Z1.4 and MIL-STD-105E (and ISO 2859-1): Table I gives a
 * code letter from the lot size and inspection level, and Tables II-A, II-B and II-C the sample size, acceptance number
 * (Ac) and rejection number (Re) for that letter and the AQL under normal, tightened and reduced inspection.
 *
 * <p>The plans below are the tables with every arrow already followed, so a cell holds the plan actually used, its
 * sample size included. Under normal and tightened inspection Re is always Ac + 1. Under reduced inspection it can be
 * higher: a lot with more than Ac but fewer than Re nonconforming units is accepted, and inspection returns to normal.
 * Values transcribed from the AQLSchemes package's tables and checked against published examples in the tests.
 */
public final class AqlTable {

    /** The general levels, II being the usual one, and the special levels for small samples. */
    public enum Level {
        I, II, III, S1, S2, S3, S4;

        public String label() {
            return name().startsWith("S") ? "S-" + name().substring(1) : name();
        }
    }

    /** How hard a series of lots is being inspected, which the switching rules move between. */
    public enum Severity { NORMAL, TIGHTENED, REDUCED }

    /** @param codeLetter the letter Table I gives the lot, before any arrow is followed */
    public record Plan(char codeLetter, int sampleSize, int acceptanceNumber, int rejectionNumber) {
    }

    /** The AQL columns this table offers, in percent nonconforming. */
    public static final List<BigDecimal> AQLS = List.of("0.010", "0.015", "0.025", "0.040", "0.065", "0.10", "0.15",
            "0.25", "0.40", "0.65", "1.0", "1.5", "2.5", "4.0", "6.5", "10").stream().map(BigDecimal::new).toList();

    private static final char[] LETTERS = "ABCDEFGHJKLMNPQR".toCharArray();

    /** Upper lot size of each row of Table I, and the letter index each level gives it. */
    private static final long[] LOT_LIMITS = {8, 15, 25, 50, 90, 150, 280, 500, 1200, 3200, 10_000, 35_000, 150_000,
            500_000, Long.MAX_VALUE};
    private static final int[][] LEVEL_LETTERS = {
            // I, II, III, S-1, S-2, S-3, S-4
            {0, 0, 1, 0, 0, 0, 0}, {0, 1, 2, 0, 0, 0, 0}, {1, 2, 3, 0, 0, 1, 1}, {2, 3, 4, 0, 1, 1, 2},
            {2, 4, 5, 1, 1, 2, 2}, {3, 5, 6, 1, 1, 2, 3}, {4, 6, 7, 1, 2, 3, 4}, {5, 7, 8, 1, 2, 3, 4},
            {6, 8, 9, 2, 2, 4, 5}, {7, 9, 10, 2, 3, 4, 6}, {8, 10, 11, 2, 3, 5, 6}, {9, 11, 12, 2, 3, 5, 7},
            {10, 12, 13, 3, 4, 6, 8}, {11, 13, 14, 3, 4, 6, 8}, {12, 14, 15, 3, 4, 7, 9}};

    /** Rows A to R, separated by "|"; each cell is "sample size/Ac/Re" for the AQLs above, in order. */
    private static final int[][][] NORMAL = parse(
            "1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 13/0/1 8/0/1 5/0/1 3/0/1 2/0/1 5/1/2" + "|" +
            "1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 13/0/1 8/0/1 5/0/1 3/0/1 2/0/1 5/1/2" + "|" +
            "1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 13/0/1 8/0/1 5/0/1 3/0/1 8/1/2 5/1/2" + "|" +
            "1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 13/0/1 8/0/1 5/0/1 13/1/2 8/1/2 8/2/3" + "|" +
            "1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 13/0/1 8/0/1 20/1/2 13/1/2 13/2/3 13/3/4" + "|" +
            "1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 13/0/1 32/1/2 20/1/2 20/2/3 20/3/4 20/5/6" + "|" +
            "1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 50/1/2 32/1/2 32/2/3 32/3/4 32/5/6 32/7/8" + "|" +
            "1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 80/1/2 50/1/2 50/2/3 50/3/4 50/5/6 50/7/8 50/10/11" + "|" +
            "1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 125/1/2 80/1/2 80/2/3 80/3/4 80/5/6 80/7/8 80/10/11 80/14/15" + "|" +
            "1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 200/1/2 125/1/2 125/2/3 125/3/4 125/5/6 125/7/8 125/10/11 125/14/15 125/21/22" + "|" +
            "1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 315/1/2 200/1/2 200/2/3 200/3/4 200/5/6 200/7/8 200/10/11 200/14/15 200/21/22 125/21/22" + "|" +
            "1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 500/1/2 315/1/2 315/2/3 315/3/4 315/5/6 315/7/8 315/10/11 315/14/15 315/21/22 200/21/22 125/21/22" + "|" +
            "1250/0/1 800/0/1 500/0/1 315/0/1 800/1/2 500/1/2 500/2/3 500/3/4 500/5/6 500/7/8 500/10/11 500/14/15 500/21/22 315/21/22 200/21/22 125/21/22" + "|" +
            "1250/0/1 800/0/1 500/0/1 1250/1/2 800/1/2 800/2/3 800/3/4 800/5/6 800/7/8 800/10/11 800/14/15 800/21/22 500/21/22 315/21/22 200/21/22 125/21/22" + "|" +
            "1250/0/1 800/0/1 2000/1/2 1250/1/2 1250/2/3 1250/3/4 1250/5/6 1250/7/8 1250/10/11 1250/14/15 1250/21/22 800/21/22 500/21/22 315/21/22 200/21/22 125/21/22" + "|" +
            "1250/0/1 800/0/1 2000/1/2 2000/2/3 2000/3/4 2000/5/6 2000/7/8 2000/10/11 2000/14/15 2000/21/22 1250/21/22 800/21/22 500/21/22 315/21/22 200/21/22 125/21/22");

    private static final int[][][] TIGHTENED = parse(
            "2000/0/1 1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 13/0/1 8/0/1 5/0/1 3/0/1 8/1/2" + "|" +
            "2000/0/1 1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 13/0/1 8/0/1 5/0/1 3/0/1 8/1/2" + "|" +
            "2000/0/1 1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 13/0/1 8/0/1 5/0/1 13/1/2 8/1/2" + "|" +
            "2000/0/1 1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 13/0/1 8/0/1 20/1/2 13/1/2 8/1/2" + "|" +
            "2000/0/1 1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 13/0/1 32/1/2 20/1/2 13/1/2 13/2/3" + "|" +
            "2000/0/1 1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 50/1/2 32/1/2 20/1/2 20/2/3 20/3/4" + "|" +
            "2000/0/1 1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 80/1/2 50/1/2 32/1/2 32/2/3 32/3/4 32/5/6" + "|" +
            "2000/0/1 1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 125/1/2 80/1/2 50/1/2 50/2/3 50/3/4 50/5/6 50/8/9" + "|" +
            "2000/0/1 1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 200/1/2 125/1/2 80/1/2 80/2/3 80/3/4 80/5/6 80/8/9 80/12/13" + "|" +
            "2000/0/1 1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 125/0/1 315/1/2 200/1/2 125/1/2 125/2/3 125/3/4 125/5/6 125/8/9 125/12/13 125/18/19" + "|" +
            "2000/0/1 1250/0/1 800/0/1 500/0/1 315/0/1 200/0/1 500/1/2 315/1/2 200/1/2 200/2/3 200/3/4 200/5/6 200/8/9 200/12/13 200/18/19 125/18/19" + "|" +
            "2000/0/1 1250/0/1 800/0/1 500/0/1 315/0/1 800/1/2 500/1/2 315/1/2 315/2/3 315/3/4 315/5/6 315/8/9 315/12/13 315/18/19 200/18/19 125/18/19" + "|" +
            "2000/0/1 1250/0/1 800/0/1 500/0/1 1250/1/2 800/1/2 500/1/2 500/2/3 500/3/4 500/5/6 500/8/9 500/12/13 500/18/19 315/18/19 200/18/19 125/18/19" + "|" +
            "2000/0/1 1250/0/1 800/0/1 2000/1/2 1250/1/2 800/1/2 800/2/3 800/3/4 800/5/6 800/8/9 800/12/13 800/18/19 500/18/19 315/18/19 200/18/19 125/18/19" + "|" +
            "2000/0/1 1250/0/1 3150/1/2 2000/1/2 1250/1/2 1250/2/3 1250/3/4 1250/5/6 1250/8/9 1250/12/13 1250/18/19 800/18/19 500/18/19 315/18/19 200/18/19 125/18/19" + "|" +
            "2000/0/1 1250/0/1 3150/1/2 2000/1/2 2000/2/3 2000/3/4 2000/5/6 2000/8/9 2000/12/13 2000/18/19 1250/18/19 800/18/19 500/18/19 315/18/19 200/18/19 125/18/19");

    private static final int[][][] REDUCED = parse(
            "500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 13/0/1 8/0/1 5/0/1 3/0/1 2/0/1 2/0/1 2/0/1 2/0/2" + "|" +
            "500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 13/0/1 8/0/1 5/0/1 3/0/1 2/0/1 2/0/1 2/0/1 2/0/2" + "|" +
            "500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 13/0/1 8/0/1 5/0/1 3/0/1 2/0/1 2/0/1 3/0/2 2/0/2" + "|" +
            "500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 13/0/1 8/0/1 5/0/1 3/0/1 2/0/1 5/0/2 3/0/2 3/1/3" + "|" +
            "500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 13/0/1 8/0/1 5/0/1 3/0/1 8/0/2 5/0/2 5/1/3 5/1/4" + "|" +
            "500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 13/0/1 8/0/1 5/0/1 13/0/2 8/0/2 8/1/3 8/1/4 8/2/5" + "|" +
            "500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 13/0/1 8/0/1 20/0/2 13/0/2 13/1/3 13/1/4 13/2/5 13/3/6" + "|" +
            "500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 13/0/1 32/0/2 20/0/2 20/1/3 20/1/4 20/2/5 20/3/6 20/5/8" + "|" +
            "500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 20/0/1 50/0/2 32/0/2 32/1/3 32/1/4 32/2/5 32/3/6 32/5/8 32/7/10" + "|" +
            "500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 32/0/1 80/0/2 50/0/2 50/1/3 50/1/4 50/2/5 50/3/6 50/5/8 50/7/10 50/10/13" + "|" +
            "500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 50/0/1 125/0/2 80/0/2 80/1/3 80/1/4 80/2/5 80/3/6 80/5/8 80/7/10 80/10/13 50/10/13" + "|" +
            "500/0/1 315/0/1 200/0/1 125/0/1 80/0/1 200/0/2 125/0/2 125/1/3 125/1/4 125/2/5 125/3/6 125/5/8 125/7/10 125/10/13 80/10/13 50/10/13" + "|" +
            "500/0/1 315/0/1 200/0/1 125/0/1 315/0/2 200/0/2 200/1/3 200/1/4 200/2/5 200/3/6 200/5/8 200/7/10 200/10/13 125/10/13 80/10/13 50/10/13" + "|" +
            "500/0/1 315/0/1 200/0/1 500/0/2 315/0/2 315/1/3 315/1/4 315/2/5 315/3/6 315/5/8 315/7/10 315/10/13 200/10/13 125/10/13 80/10/13 50/10/13" + "|" +
            "500/0/1 315/0/1 800/0/2 500/0/2 500/1/3 500/1/4 500/2/5 500/3/6 500/5/8 500/7/10 500/10/13 315/10/13 200/10/13 125/10/13 80/10/13 50/10/13" + "|" +
            "500/0/1 315/0/1 800/0/2 800/1/3 800/1/4 800/2/5 800/3/6 800/5/8 800/7/10 800/10/13 500/10/13 315/10/13 200/10/13 125/10/13 80/10/13 50/10/13");

    private AqlTable() {
    }

    private static int[][][] parse(String table) {
        String[] rows = table.split("\\|");
        int[][][] plans = new int[rows.length][][];
        for (int row = 0; row < rows.length; row++) {
            String[] cells = rows[row].trim().split(" ");
            plans[row] = new int[cells.length][];
            for (int column = 0; column < cells.length; column++) {
                String[] parts = cells[column].split("/");
                plans[row][column] = new int[] {
                        Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
            }
        }
        return plans;
    }

    /** @return the letter index from Table I. A lot of fewer than two units counts as the smallest row. */
    static int letterFor(long lotSize, Level level) {
        int row = 0;
        while (lotSize > LOT_LIMITS[row]) {
            row++;
        }
        return LEVEL_LETTERS[row][level.ordinal()];
    }

    /** Normal inspection's plan for a lot. */
    public static Plan plan(long lotSize, Level level, BigDecimal aql) {
        return plan(lotSize, level, aql, Severity.NORMAL);
    }

    /**
     * The plan for a lot. When the sample size is at least the lot size, every unit is inspected, with the plan's
     * acceptance and rejection numbers.
     */
    public static Plan plan(long lotSize, Level level, BigDecimal aql, Severity severity) {
        int letter = letterFor(lotSize, level);
        int[][][] table = switch (severity) {
            case NORMAL -> NORMAL;
            case TIGHTENED -> TIGHTENED;
            case REDUCED -> REDUCED;
        };
        int[] cell = table[letter][column(aql)];
        int sampleSize = (int) Math.min(cell[0], Math.max(lotSize, 1));
        return new Plan(LETTERS[letter], sampleSize, cell[1], cell[2]);
    }

    /** @return the AQL one column tighter, or null when this is the tightest */
    public static BigDecimal tighter(BigDecimal aql) {
        int column = column(aql);
        return column == 0 ? null : AQLS.get(column - 1);
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
