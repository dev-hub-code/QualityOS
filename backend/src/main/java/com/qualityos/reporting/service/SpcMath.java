package com.qualityos.reporting.service;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.reporting.dto.SpcDtos.Capability;
import com.qualityos.reporting.dto.SpcDtos.ChartType;
import com.qualityos.reporting.dto.SpcDtos.Excluded;
import com.qualityos.reporting.dto.SpcDtos.Point;
import com.qualityos.reporting.dto.SpcDtos.Signal;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Phase I control charts and capability, worked out from the readings alone. Subgroup sizes may differ (AQL sampling
 * takes different sample sizes), so each point's limits come from its own n around one pooled estimate of σ: with
 * equal sizes these are the textbook A2, D3 and D4 limits. Statistics are returned to 6 significant figures.
 */
final class SpcMath {

    /** Subgroups (or readings for I-MR) below which the figures are shown but not yet reliable. */
    static final int RELIABLE = 25;
    static final int MAX_SUBGROUP = 25;
    static final int RUN = 8;

    /** d₂ and d₃ for n = 2 to 25, index n − 2. */
    private static final double[] D2 = {1.128, 1.693, 2.059, 2.326, 2.534, 2.704, 2.847, 2.970, 3.078, 3.173,
        3.258, 3.336, 3.407, 3.472, 3.532, 3.588, 3.640, 3.689, 3.735, 3.778, 3.819, 3.858, 3.895, 3.931};
    private static final double[] D3 = {0.853, 0.888, 0.880, 0.864, 0.848, 0.833, 0.820, 0.808, 0.797, 0.787,
        0.778, 0.770, 0.763, 0.756, 0.750, 0.744, 0.739, 0.734, 0.729, 0.724, 0.720, 0.716, 0.712, 0.708};
    private static final double MR_D2 = 1.128;
    private static final double MR_D4 = 3.267;
    private static final double I_FACTOR = 2.66;

    private SpcMath() {
    }

    record Reading(int sampleNumber, double value) {
    }

    /** One inspection's readings of the parameter, in sample order. */
    record Subgroup(UUID inspectionId, String inspectionNumber, String batchNumber, Instant submittedAt,
            List<Reading> readings) {

        int n() {
            return readings.size();
        }
    }

    record Spec(Double lsl, Double usl) {
    }

    record Result(ChartType chartType, double centre, double sigma, Capability capability, List<Point> points,
            Excluded excluded, List<String> warnings) {
    }

    static double d2(int n) {
        return D2[n - 2];
    }

    static double d3(int n) {
        return D3[n - 2];
    }

    /** X̄-R when at least half the subgroups have two readings or more; otherwise I-MR. */
    static ChartType auto(List<Subgroup> subgroups) {
        long several = subgroups.stream().filter(subgroup -> subgroup.n() >= 2).count();
        return several * 2 >= subgroups.size() ? ChartType.XBAR_R : ChartType.I_MR;
    }

    static Result xbarR(List<Subgroup> subgroups, Spec spec) {
        int single = 0;
        int tooLarge = 0;
        List<Subgroup> used = new ArrayList<>();
        for (Subgroup subgroup : subgroups) {
            if (subgroup.n() < 2) {
                single++;
            } else if (subgroup.n() > MAX_SUBGROUP) {
                tooLarge++;
            } else {
                used.add(subgroup);
            }
        }
        if (used.size() < 2) {
            throw new BusinessRuleException(("An X̄-R chart needs at least 2 inspections with 2 to %d readings, and "
                    + "this period has %d. Try the individuals chart.").formatted(MAX_SUBGROUP, used.size()));
        }
        int k = used.size();
        double[] means = new double[k];
        double[] ranges = new double[k];
        double sigmaSum = 0;
        double weighted = 0;
        long readings = 0;
        for (int i = 0; i < k; i++) {
            List<Reading> values = used.get(i).readings();
            double sum = 0;
            double low = Double.POSITIVE_INFINITY;
            double high = Double.NEGATIVE_INFINITY;
            for (Reading reading : values) {
                sum += reading.value();
                low = Math.min(low, reading.value());
                high = Math.max(high, reading.value());
            }
            means[i] = sum / values.size();
            ranges[i] = high - low;
            sigmaSum += ranges[i] / d2(values.size());
            weighted += sum;
            readings += values.size();
        }
        double sigma = sigmaSum / k;
        double centre = weighted / readings;
        boolean[] run = runs(means, centre);
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            Subgroup subgroup = used.get(i);
            int n = subgroup.n();
            double spread = 3 * sigma / Math.sqrt(n);
            double rangeCentre = d2(n) * sigma;
            double rangeUcl = (d2(n) + 3 * d3(n)) * sigma;
            double rangeLcl = Math.max(0, (d2(n) - 3 * d3(n)) * sigma);
            List<Signal> signals = signals(beyond(means[i], centre - spread, centre + spread),
                    beyond(ranges[i], rangeLcl, rangeUcl), run[i]);
            points.add(new Point(subgroup.inspectionId(), subgroup.inspectionNumber(), subgroup.batchNumber(),
                    subgroup.submittedAt(), null, n, round(means[i]), round(centre - spread), round(centre + spread),
                    round(ranges[i]), round(rangeCentre), round(rangeLcl), round(rangeUcl), signals));
        }
        List<String> warnings = new ArrayList<>();
        if (k < RELIABLE) {
            warnings.add("Fewer than %d subgroups: the limits and capability are not yet reliable.".formatted(RELIABLE));
        }
        Capability capability = capability(centre, sigma, spec, warnings);
        return new Result(ChartType.XBAR_R, round(centre), round(sigma), capability, points,
                new Excluded(single, tooLarge), warnings);
    }

    /** Every reading in order: the subgroups as given, each in sample order. */
    static Result individuals(List<Subgroup> subgroups, Spec spec) {
        List<Subgroup> owners = new ArrayList<>();
        List<Reading> readings = new ArrayList<>();
        subgroups.forEach(subgroup -> subgroup.readings().forEach(reading -> {
            owners.add(subgroup);
            readings.add(reading);
        }));
        int m = readings.size();
        if (m < 2) {
            throw new BusinessRuleException(
                    "An individuals chart needs at least 2 readings, and this period has %d.".formatted(m));
        }
        double[] values = readings.stream().mapToDouble(Reading::value).toArray();
        double sum = 0;
        double movingSum = 0;
        for (int j = 0; j < m; j++) {
            sum += values[j];
            if (j > 0) {
                movingSum += Math.abs(values[j] - values[j - 1]);
            }
        }
        double centre = sum / m;
        double movingRange = movingSum / (m - 1);
        double sigma = movingRange / MR_D2;
        double ucl = centre + I_FACTOR * movingRange;
        double lcl = centre - I_FACTOR * movingRange;
        double rangeUcl = MR_D4 * movingRange;
        boolean[] run = runs(values, centre);
        List<Point> points = new ArrayList<>();
        for (int j = 0; j < m; j++) {
            Subgroup owner = owners.get(j);
            Double range = j == 0 ? null : Math.abs(values[j] - values[j - 1]);
            List<Signal> signals = signals(beyond(values[j], lcl, ucl), range != null && beyond(range, 0, rangeUcl),
                    run[j]);
            points.add(new Point(owner.inspectionId(), owner.inspectionNumber(), owner.batchNumber(),
                    owner.submittedAt(), readings.get(j).sampleNumber(), 1, values[j], round(lcl), round(ucl),
                    range == null ? null : round(range), round(movingRange), 0.0, round(rangeUcl), signals));
        }
        List<String> warnings = new ArrayList<>();
        if (m < RELIABLE) {
            warnings.add("Fewer than %d readings: the limits and capability are not yet reliable.".formatted(RELIABLE));
        }
        Capability capability = capability(centre, sigma, spec, warnings);
        return new Result(ChartType.I_MR, round(centre), round(sigma), capability, points, new Excluded(0, 0),
                warnings);
    }

    /** Cp needs both limits; with one, Cpk is the one-sided CPU or CPL. */
    static Capability capability(double centre, double sigma, Spec spec, List<String> warnings) {
        if (spec.lsl() == null && spec.usl() == null) {
            warnings.add("The parameter has no specification limits, so Cp and Cpk cannot be worked out.");
            return new Capability(null, null);
        }
        if (sigma <= 0) {
            warnings.add("The readings do not vary, so Cp and Cpk cannot be worked out.");
            return new Capability(null, null);
        }
        Double cp = spec.lsl() != null && spec.usl() != null ? (spec.usl() - spec.lsl()) / (6 * sigma) : null;
        double cpk = Double.POSITIVE_INFINITY;
        if (spec.usl() != null) {
            cpk = Math.min(cpk, (spec.usl() - centre) / (3 * sigma));
        }
        if (spec.lsl() != null) {
            cpk = Math.min(cpk, (centre - spec.lsl()) / (3 * sigma));
        }
        return new Capability(cp == null ? null : round(cp), round(cpk));
    }

    /**
     * Points in a run of {@link #RUN} or more on one side of the centre line. A point on the line ends the run and
     * belongs to none.
     */
    static boolean[] runs(double[] values, double centre) {
        boolean[] flagged = new boolean[values.length];
        int start = 0;
        int side = 0;
        for (int i = 0; i <= values.length; i++) {
            int current = i == values.length ? 0 : side(values[i], centre);
            if (i == values.length || current != side || current == 0) {
                if (side != 0 && i - start >= RUN) {
                    for (int j = start; j < i; j++) {
                        flagged[j] = true;
                    }
                }
                start = i;
                side = current;
            }
        }
        return flagged;
    }

    private static int side(double value, double centre) {
        if (Math.abs(value - centre) <= tolerance(centre)) {
            return 0;
        }
        return value > centre ? 1 : -1;
    }

    /** Outside the limits by more than floating-point noise. */
    private static boolean beyond(double value, double lower, double upper) {
        double slack = tolerance(Math.max(Math.abs(lower), Math.abs(upper)));
        return value > upper + slack || value < lower - slack;
    }

    private static double tolerance(double scale) {
        return 1e-9 * Math.max(1, Math.abs(scale));
    }

    private static List<Signal> signals(boolean beyond, boolean rangeBeyond, boolean run) {
        List<Signal> signals = new ArrayList<>();
        if (beyond) {
            signals.add(Signal.BEYOND_LIMITS);
        }
        if (rangeBeyond) {
            signals.add(Signal.RANGE_BEYOND_LIMITS);
        }
        if (run) {
            signals.add(Signal.RUN_OF_8);
        }
        return List.copyOf(signals);
    }

    /** To 6 significant figures. */
    static double round(double value) {
        if (value == 0 || !Double.isFinite(value)) {
            return value;
        }
        return new BigDecimal(value).round(new MathContext(6)).doubleValue();
    }
}
