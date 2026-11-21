package com.qualityos.inspection.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Statistical process control on the numeric measurements inspections record. */
public final class SpcDtos {

    private SpcDtos() {
    }

    /**
     * XBAR_R: one point per inspection, the mean and range of its samples. I_MR: one point per measurement, with the
     * moving range from the one before.
     */
    public enum ChartType {
        XBAR_R, I_MR
    }

    /** A numeric parameter of a template that has measurements, which can be charted. */
    public record Characteristic(UUID templateId, String templateCode, String templateName, String inspectionType,
            String parameterCode, String parameterName, long measurements, Instant lastMeasuredAt) {
    }

    /** A centre line with its control limits; {@code lower} is null where no lower limit applies. */
    public record Limits(double centre, double upper, Double lower) {
    }

    /**
     * One point of the chart. {@code value} is a subgroup's mean, or one measurement; {@code range} is its range, or
     * the moving range from the point before (null for the first). Out of control: beyond the control limits.
     */
    public record Point(UUID inspectionId, String inspectionNumber, String batchNumber, Instant measuredAt,
            int samples, double value, Double range, boolean outOfControl, boolean rangeOutOfControl) {
    }

    /**
     * How the process fits its specification. Cp and Pp need both limits. Cp and Cpk use the variation within
     * subgroups (or between neighbouring measurements); Pp and Ppk the overall standard deviation. Null where it
     * cannot be worked out, such as when every measurement is the same.
     */
    public record Capability(int measurements, double mean, Double sigmaWithin, Double sigmaOverall, Double cp,
            Double cpk, Double pp, Double ppk) {
    }

    /**
     * {@code lowerSpec} and {@code upperSpec}: the specification of the latest inspection; {@code specChanged} when an
     * earlier one in the period had another. {@code subgroupSize}: the samples each X̄-R point takes, and
     * {@code excludedSubgroups} the inspections with another number that were left out. {@code preliminary} with
     * fewer than 20 points, when the limits may still move. {@code truncated} when only the latest points are shown.
     */
    public record SpcChart(UUID templateId, String templateCode, String templateName, String parameterCode,
            String parameterName, String unit, ChartType chartType, LocalDate from, LocalDate to,
            Integer subgroupSize, int excludedSubgroups, Double lowerSpec, Double upperSpec, Double nominal,
            boolean specChanged, Limits valueLimits, Limits rangeLimits, List<Point> points, Capability capability,
            boolean preliminary, boolean truncated) {
    }
}
