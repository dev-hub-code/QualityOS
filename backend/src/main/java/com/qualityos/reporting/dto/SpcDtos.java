package com.qualityos.reporting.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Statistical process control: control charts of a numeric inspection parameter, and its capability. */
public final class SpcDtos {

    private SpcDtos() {
    }

    /** The chart asked for: AUTO lets the readings decide. */
    public enum ChartChoice {
        AUTO, XBAR_R, I_MR
    }

    public enum ChartType {
        XBAR_R, I_MR
    }

    public enum Signal {
        /** A subgroup mean or individual reading outside its control limits. */
        BEYOND_LIMITS,
        /** A range or moving range outside its control limits. */
        RANGE_BEYOND_LIMITS,
        /** One of 8 or more means or readings in a row on the same side of the centre line. */
        RUN_OF_8
    }

    /** A parameter that can be charted: it has numeric readings in the period. */
    public record SpcParameter(UUID templateId, String templateCode, String templateName, String parameterCode,
            String parameterName, long subgroups, Instant lastSubmittedAt) {
    }

    /** The parameter as the latest charted inspection's specification has it. */
    public record ParameterSpec(String code, String name, String unit, Double lsl, Double usl, Double nominal,
            boolean specChanged) {
    }

    /** Empty when it cannot be worked out; Cp needs both limits. */
    public record Capability(Double cp, Double cpk) {
    }

    /**
     * A subgroup (one inspection) on an X̄-R chart, or one reading on an I-MR chart. Limits are the point's own, as
     * they step with the subgroup size. {@code range} is the moving range on I-MR, empty for the first reading, and only I-MR
     * points carry a sample number.
     */
    public record Point(UUID inspectionId, String inspectionNumber, String batchNumber, Instant submittedAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer sampleNumber, int n, double value, double lcl, double ucl, Double range, Double rangeCentre,
            Double rangeLcl, Double rangeUcl, List<Signal> signals) {
    }

    /** Inspections an X̄-R chart leaves out: those with one reading, and those with more than 25. */
    public record Excluded(int singleReading, int tooLarge) {
    }

    public record SpcChart(UUID templateId, String templateCode, String templateName, LocalDate from, LocalDate to,
            ChartType chartType, ParameterSpec parameter, double centre, double sigma, Capability capability,
            List<Point> points, Excluded excluded, boolean truncated, List<String> warnings) {
    }
}
