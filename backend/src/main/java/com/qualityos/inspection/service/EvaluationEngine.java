package com.qualityos.inspection.service;

import com.qualityos.inspection.dto.TemplateDtos.ParameterSpec;
import com.qualityos.inspection.entity.Inspection.Evaluation;
import com.qualityos.inspection.entity.InspectionParameter.Criticality;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Judges measurements against a specification. It only states what the numbers say: what then happens to the
 * material is a separate, human decision.
 */
public final class EvaluationEngine {

    /** A value entered for one sample. Only the field matching the parameter's type is set. */
    public record Value(BigDecimal numeric, String text, Boolean bool, LocalDate date) {

        boolean isEmpty() {
            return numeric == null && (text == null || text.isBlank()) && bool == null && date == null;
        }
    }

    /** @param nonconformingUnits under AQL sampling, the sampled units with a failure; otherwise 0 */
    public record Outcome(Evaluation evaluation, boolean criticalFailure, int nonconformingUnits) {

        public Outcome(Evaluation evaluation, boolean criticalFailure) {
            this(evaluation, criticalFailure, 0);
        }
    }

    private EvaluationEngine() {
    }

    public static Evaluation evaluate(ParameterSpec spec, Value value) {
        return switch (spec.dataType()) {
            case NUMERIC -> evaluateNumber(spec, value.numeric());
            case BOOLEAN -> evaluateBoolean(spec, value.bool());
            case TEXT -> evaluateText(spec, value.text());
            case DATE -> Evaluation.PENDING_REVIEW;
        };
    }

    /** Limits are inclusive: a measurement exactly on a limit conforms. */
    private static Evaluation evaluateNumber(ParameterSpec spec, BigDecimal measured) {
        BigDecimal low = spec.minValue();
        BigDecimal high = spec.maxValue();
        if (spec.nominalValue() != null && spec.tolerance() != null) {
            low = low != null ? low : spec.nominalValue().subtract(spec.tolerance());
            high = high != null ? high : spec.nominalValue().add(spec.tolerance());
        }
        if (low == null && high == null) {
            return spec.nominalValue() == null
                    ? Evaluation.PENDING_REVIEW
                    : pass(spec.nominalValue().compareTo(measured) == 0);
        }
        return pass((low == null || measured.compareTo(low) >= 0) && (high == null || measured.compareTo(high) <= 0));
    }

    private static Evaluation evaluateBoolean(ParameterSpec spec, Boolean actual) {
        Boolean expected = YesNo.parse(spec.expectedText());
        return expected == null ? Evaluation.PENDING_REVIEW : pass(expected.equals(actual));
    }

    private static Evaluation evaluateText(ParameterSpec spec, String actual) {
        if (spec.expectedText() == null || spec.expectedText().isBlank()) {
            return Evaluation.PENDING_REVIEW;
        }
        return pass(spec.expectedText().trim().equalsIgnoreCase(actual.trim()));
    }

    private static Evaluation pass(boolean conforms) {
        return conforms ? Evaluation.PASS : Evaluation.FAIL;
    }

    /**
     * Combines the evaluations of a whole inspection. A single failed critical sample fails it. For other
     * parameters, up to {@code acceptanceNumber} failed samples each are tolerated, as the sampling plan allows.
     *
     * @param byParameter each parameter's sample evaluations, keyed by parameter code
     */
    public static Outcome overall(
            List<ParameterSpec> parameters, Map<String, List<Evaluation>> byParameter, int acceptanceNumber) {
        boolean failed = false;
        boolean criticalFailure = false;
        boolean review = false;
        for (ParameterSpec spec : parameters) {
            List<Evaluation> evaluations = byParameter.getOrDefault(spec.code(), List.of());
            long failures = evaluations.stream().filter(Evaluation.FAIL::equals).count();
            review |= evaluations.contains(Evaluation.PENDING_REVIEW);
            if (spec.criticality() == Criticality.CRITICAL && failures > 0) {
                criticalFailure = true;
            }
            failed |= failures > acceptanceNumber;
        }
        Evaluation evaluation = criticalFailure || failed
                ? Evaluation.FAIL
                : review ? Evaluation.PENDING_REVIEW : Evaluation.PASS;
        return new Outcome(evaluation, criticalFailure);
    }

    /** One sample's evaluation of one parameter. */
    public record SampleEvaluation(String parameterCode, int sampleNumber, Evaluation evaluation) {
    }

    /**
     * Combines the evaluations of an inspection sampled by AQL, which counts nonconforming units: a sample with any
     * failed parameter is one, however many parameters it failed. The lot fails when more than {@code acceptanceNumber}
     * samples are nonconforming. A failed critical sample still fails it, and a parameter checked once for the whole
     * lot fails it when that check fails.
     */
    public static Outcome overallByUnits(
            List<ParameterSpec> parameters, List<SampleEvaluation> results, int acceptanceNumber) {
        return overallByUnits(parameters, results, acceptanceNumber, acceptanceNumber + 1);
    }

    /**
     * As above, with a rejection number that may sit above Ac + 1, as under reduced inspection: between the two the
     * lot is still accepted.
     */
    public static Outcome overallByUnits(
            List<ParameterSpec> parameters, List<SampleEvaluation> results, int acceptanceNumber, int rejectsFrom) {
        java.util.Map<String, ParameterSpec> specs = parameters.stream()
                .collect(java.util.stream.Collectors.toMap(ParameterSpec::code, spec -> spec));
        boolean criticalFailure = false;
        boolean lotFailure = false;
        boolean review = false;
        java.util.Set<Integer> nonconforming = new java.util.HashSet<>();
        for (SampleEvaluation result : results) {
            ParameterSpec spec = specs.get(result.parameterCode());
            if (spec == null) {
                continue;
            }
            review |= result.evaluation() == Evaluation.PENDING_REVIEW;
            if (result.evaluation() != Evaluation.FAIL) {
                continue;
            }
            if (spec.criticality() == Criticality.CRITICAL) {
                criticalFailure = true;
            } else if (spec.perSample()) {
                nonconforming.add(result.sampleNumber());
            } else {
                lotFailure = true;
            }
        }
        Evaluation evaluation = criticalFailure || lotFailure || nonconforming.size() >= rejectsFrom
                ? Evaluation.FAIL
                : review ? Evaluation.PENDING_REVIEW : Evaluation.PASS;
        return new Outcome(evaluation, criticalFailure, nonconforming.size());
    }
}
