package com.qualityos.inspection.service;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.inspection.dto.SpcDtos.Capability;
import com.qualityos.inspection.dto.SpcDtos.ChartType;
import com.qualityos.inspection.dto.SpcDtos.Limits;
import com.qualityos.inspection.dto.SpcDtos.Point;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Control limits and capability from measurements, by the usual Shewhart constants. It only does the arithmetic: which
 * measurements to chart is the caller's choice.
 */
public final class SpcCalculator {

    /** Points below this many leave the limits preliminary. */
    public static final int SETTLED_POINTS = 20;

    /** d2 for a moving range of two. */
    private static final double D2_OF_TWO = 1.128;

    /** By subgroup size 2 to 10: A2, D3, D4 and d2. */
    private static final double[][] CONSTANTS = {
            {1.880, 0, 3.267, 1.128},
            {1.023, 0, 2.574, 1.693},
            {0.729, 0, 2.282, 2.059},
            {0.577, 0, 2.114, 2.326},
            {0.483, 0, 2.004, 2.534},
            {0.419, 0.076, 1.924, 2.704},
            {0.373, 0.136, 1.864, 2.847},
            {0.337, 0.184, 1.816, 2.970},
            {0.308, 0.223, 1.777, 3.078},
    };

    /** One inspection's measurements of the parameter, in sample order. */
    public record Subgroup(UUID inspectionId, String inspectionNumber, String batchNumber, Instant measuredAt,
            List<Double> values) {
    }

    /** {@code lower} or {@code upper} may be null for a one-sided specification. */
    public record Spec(Double lower, Double upper) {
    }

    public record Result(ChartType chartType, Integer subgroupSize, int excludedSubgroups, Limits valueLimits,
            Limits rangeLimits, List<Point> points, Capability capability, boolean preliminary) {
    }

    private SpcCalculator() {
    }

    /** The chart a parameter suits: X̄-R when inspections usually take several samples of it, I-MR otherwise. */
    public static ChartType suitedTo(List<Subgroup> subgroups) {
        Integer size = usualSize(subgroups);
        return size != null && size >= 2 && size <= CONSTANTS.length + 1 ? ChartType.XBAR_R : ChartType.I_MR;
    }

    /** Subgroups in time order. */
    public static Result calculate(ChartType type, List<Subgroup> subgroups, Spec spec) {
        return type == ChartType.XBAR_R ? xbarR(subgroups, spec) : individuals(subgroups, spec);
    }

    private static Result xbarR(List<Subgroup> subgroups, Spec spec) {
        Integer size = usualSize(subgroups);
        if (size == null || size < 2) {
            throw new BusinessRuleException("Each inspection measures this once: use the individuals chart.");
        }
        if (size > CONSTANTS.length + 1) {
            throw new BusinessRuleException(("X̄-R charts take 2 to 10 samples an inspection, and these take %d: use "
                    + "the individuals chart.").formatted(size));
        }
        List<Subgroup> used = subgroups.stream().filter(subgroup -> subgroup.values().size() == size).toList();
        if (used.size() < 2) {
            throw new BusinessRuleException("A chart needs at least two inspections with measurements.");
        }
        double[] constants = CONSTANTS[size - 2];
        double[] means = used.stream().mapToDouble(subgroup -> mean(subgroup.values())).toArray();
        double[] ranges = used.stream().mapToDouble(subgroup -> range(subgroup.values())).toArray();
        double grandMean = mean(means);
        double meanRange = mean(ranges);
        Limits valueLimits = new Limits(grandMean, grandMean + constants[0] * meanRange,
                grandMean - constants[0] * meanRange);
        Limits rangeLimits = new Limits(meanRange, constants[2] * meanRange, constants[1] * meanRange);
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < used.size(); i++) {
            Subgroup subgroup = used.get(i);
            points.add(new Point(subgroup.inspectionId(), subgroup.inspectionNumber(), subgroup.batchNumber(),
                    subgroup.measuredAt(), size, means[i], ranges[i], outside(means[i], valueLimits),
                    outside(ranges[i], rangeLimits)));
        }
        List<Double> all = used.stream().flatMap(subgroup -> subgroup.values().stream()).toList();
        return new Result(ChartType.XBAR_R, size, subgroups.size() - used.size(), valueLimits, rangeLimits, points,
                capability(all, meanRange / constants[3], spec), used.size() < SETTLED_POINTS);
    }

    private static Result individuals(List<Subgroup> subgroups, Spec spec) {
        List<Point> points = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        List<Double> movingRanges = new ArrayList<>();
        for (Subgroup subgroup : subgroups) {
            for (double value : subgroup.values()) {
                Double movingRange = values.isEmpty() ? null : Math.abs(value - values.getLast());
                if (movingRange != null) {
                    movingRanges.add(movingRange);
                }
                values.add(value);
                points.add(new Point(subgroup.inspectionId(), subgroup.inspectionNumber(), subgroup.batchNumber(),
                        subgroup.measuredAt(), 1, value, movingRange, false, false));
            }
        }
        if (values.size() < 2) {
            throw new BusinessRuleException("A chart needs at least two measurements.");
        }
        double centre = mean(values.stream().mapToDouble(Double::doubleValue).toArray());
        double meanMovingRange = mean(movingRanges.stream().mapToDouble(Double::doubleValue).toArray());
        double spread = 3 * meanMovingRange / D2_OF_TWO;
        Limits valueLimits = new Limits(centre, centre + spread, centre - spread);
        Limits rangeLimits = new Limits(meanMovingRange, CONSTANTS[0][2] * meanMovingRange, null);
        List<Point> marked = points.stream().map(point -> new Point(point.inspectionId(), point.inspectionNumber(),
                point.batchNumber(), point.measuredAt(), 1, point.value(), point.range(),
                outside(point.value(), valueLimits), point.range() != null && outside(point.range(), rangeLimits)))
                .toList();
        return new Result(ChartType.I_MR, null, 0, valueLimits, rangeLimits, marked,
                capability(values, meanMovingRange / D2_OF_TWO, spec), values.size() < SETTLED_POINTS);
    }

    static Capability capability(List<Double> values, double sigmaWithin, Spec spec) {
        double[] all = values.stream().mapToDouble(Double::doubleValue).toArray();
        double mean = mean(all);
        double sigmaOverall = standardDeviation(all, mean);
        Double within = sigmaWithin > 0 ? sigmaWithin : null;
        Double overall = sigmaOverall > 0 ? sigmaOverall : null;
        return new Capability(all.length, mean, within, overall, potential(spec, within), actual(spec, mean, within),
                potential(spec, overall), actual(spec, mean, overall));
    }

    private static Double potential(Spec spec, Double sigma) {
        if (sigma == null || spec.lower() == null || spec.upper() == null) {
            return null;
        }
        return (spec.upper() - spec.lower()) / (6 * sigma);
    }

    /** The nearer side's distance in three sigmas, from the limits the specification has. */
    private static Double actual(Spec spec, double mean, Double sigma) {
        if (sigma == null || (spec.lower() == null && spec.upper() == null)) {
            return null;
        }
        double upper = spec.upper() == null ? Double.POSITIVE_INFINITY : (spec.upper() - mean) / (3 * sigma);
        double lower = spec.lower() == null ? Double.POSITIVE_INFINITY : (mean - spec.lower()) / (3 * sigma);
        return Math.min(upper, lower);
    }

    /** The number of samples most inspections take; the smaller on a tie. Null with no subgroups. */
    private static Integer usualSize(List<Subgroup> subgroups) {
        Map<Integer, Long> counts = subgroups.stream().collect(Collectors.groupingBy(
                subgroup -> subgroup.values().size(), TreeMap::new, Collectors.counting()));
        return counts.entrySet().stream().max(Comparator.<Map.Entry<Integer, Long>>comparingLong(Map.Entry::getValue)
                .thenComparing(Map.Entry::getKey, Comparator.reverseOrder())).map(Map.Entry::getKey).orElse(null);
    }

    private static boolean outside(double value, Limits limits) {
        // A little slack, so a point exactly on a limit is not called out by rounding.
        double slack = 1e-9 * Math.max(1, Math.abs(limits.upper()));
        return value > limits.upper() + slack || (limits.lower() != null && value < limits.lower() - slack);
    }

    private static double mean(List<Double> values) {
        return mean(values.stream().mapToDouble(Double::doubleValue).toArray());
    }

    private static double mean(double[] values) {
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    private static double range(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).max().orElse(0)
                - values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
    }

    private static double standardDeviation(double[] values, double mean) {
        if (values.length < 2) {
            return 0;
        }
        double squares = 0;
        for (double value : values) {
            squares += (value - mean) * (value - mean);
        }
        return Math.sqrt(squares / (values.length - 1));
    }
}
