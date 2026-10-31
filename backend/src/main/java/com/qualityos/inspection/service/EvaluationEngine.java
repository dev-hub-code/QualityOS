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

    public record Outcome(Evaluation evaluation, boolean criticalFailure) {
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
        if (spec.expectedText() == null || spec.expectedText().isBlank()) {
            return Evaluation.PENDING_REVIEW;
        }
        return pass(Boolean.parseBoolean(spec.expectedText().trim()) == actual);
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
}
