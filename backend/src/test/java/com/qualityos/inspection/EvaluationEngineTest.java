package com.qualityos.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import com.qualityos.inspection.dto.TemplateDtos.ParameterSpec;
import com.qualityos.inspection.entity.Inspection.Evaluation;
import com.qualityos.inspection.entity.InspectionParameter.Criticality;
import com.qualityos.inspection.entity.InspectionParameter.DataType;
import com.qualityos.inspection.service.EvaluationEngine;
import com.qualityos.inspection.service.EvaluationEngine.Outcome;
import com.qualityos.inspection.service.EvaluationEngine.Value;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EvaluationEngineTest {

    private static ParameterSpec spec(
            DataType type, String nominal, String min, String max, String tolerance, String text, Criticality level) {
        return new ParameterSpec("P", "Parameter", null, type, null, decimal(nominal), decimal(min), decimal(max),
                decimal(tolerance), text, level, true, true, false, null, null);
    }

    private static ParameterSpec numeric(String nominal, String min, String max, String tolerance) {
        return spec(DataType.NUMERIC, nominal, min, max, tolerance, null, Criticality.MINOR);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static Value number(String value) {
        return new Value(new BigDecimal(value), null, null, null);
    }

    @ParameterizedTest
    @CsvSource({"19.9,PASS", "20.1,PASS", "20,PASS", "19.899999,FAIL", "20.100001,FAIL"})
    void limitsAreInclusiveAndExact(String measured, Evaluation expected) {
        assertThat(EvaluationEngine.evaluate(numeric(null, "19.9", "20.1", null), number(measured))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"19.9,PASS", "20.1,PASS", "20.2,FAIL"})
    void toleranceSetsLimitsAroundTheNominal(String measured, Evaluation expected) {
        assertThat(EvaluationEngine.evaluate(numeric("20", null, null, "0.1"), number(measured))).isEqualTo(expected);
    }

    @Test
    void aOneSidedLimitLeavesTheOtherSideOpen() {
        assertThat(EvaluationEngine.evaluate(numeric(null, "50", null, null), number("9999"))).isEqualTo(Evaluation.PASS);
        assertThat(EvaluationEngine.evaluate(numeric(null, "50", null, null), number("49"))).isEqualTo(Evaluation.FAIL);
    }

    @Test
    void scaleDoesNotAffectEquality() {
        assertThat(EvaluationEngine.evaluate(numeric("20", null, null, null), number("20.000"))).isEqualTo(Evaluation.PASS);
    }

    @Test
    void anUnspecifiedMeasurementGoesToAHuman() {
        assertThat(EvaluationEngine.evaluate(numeric(null, null, null, null), number("1")))
                .isEqualTo(Evaluation.PENDING_REVIEW);
        ParameterSpec openText = spec(DataType.TEXT, null, null, null, null, null, Criticality.MINOR);
        assertThat(EvaluationEngine.evaluate(openText, new Value(null, "looks fine", null, null)))
                .isEqualTo(Evaluation.PENDING_REVIEW);
    }

    @Test
    void textAndBooleanCompareToTheExpectedValue() {
        ParameterSpec text = spec(DataType.TEXT, null, null, null, null, "Smooth", Criticality.MINOR);
        ParameterSpec bool = spec(DataType.BOOLEAN, null, null, null, null, "true", Criticality.MINOR);

        assertThat(EvaluationEngine.evaluate(text, new Value(null, " smooth ", null, null))).isEqualTo(Evaluation.PASS);
        assertThat(EvaluationEngine.evaluate(text, new Value(null, "Rough", null, null))).isEqualTo(Evaluation.FAIL);
        assertThat(EvaluationEngine.evaluate(bool, new Value(null, null, false, null))).isEqualTo(Evaluation.FAIL);
    }

    @Test
    void oneFailedCriticalSampleFailsEvenWithinTheAcceptanceNumber() {
        ParameterSpec critical = spec(DataType.NUMERIC, null, "1", "2", null, null, Criticality.CRITICAL);

        Outcome outcome = EvaluationEngine.overall(
                List.of(critical), Map.of("P", List.of(Evaluation.FAIL, Evaluation.PASS)), 5);

        assertThat(outcome.evaluation()).isEqualTo(Evaluation.FAIL);
        assertThat(outcome.criticalFailure()).isTrue();
    }

    @Test
    void otherFailuresAreJudgedAgainstTheAcceptanceNumber() {
        ParameterSpec minor = numeric(null, "1", "2", null);
        Map<String, List<Evaluation>> oneFailure = Map.of("P", List.of(Evaluation.FAIL, Evaluation.PASS));

        assertThat(EvaluationEngine.overall(List.of(minor), oneFailure, 1).evaluation()).isEqualTo(Evaluation.PASS);
        assertThat(EvaluationEngine.overall(List.of(minor), oneFailure, 0).evaluation()).isEqualTo(Evaluation.FAIL);
    }

    @Test
    void failureOutranksReview() {
        ParameterSpec minor = numeric(null, "1", "2", null);
        Map<String, List<Evaluation>> mixed = Map.of("P", List.of(Evaluation.FAIL, Evaluation.PENDING_REVIEW));

        assertThat(EvaluationEngine.overall(List.of(minor), mixed, 0).evaluation()).isEqualTo(Evaluation.FAIL);
        assertThat(EvaluationEngine.overall(List.of(minor), Map.of("P", List.of(Evaluation.PENDING_REVIEW)), 0).evaluation())
                .isEqualTo(Evaluation.PENDING_REVIEW);
    }
}
