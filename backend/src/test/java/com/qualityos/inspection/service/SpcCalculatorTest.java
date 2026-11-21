package com.qualityos.inspection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.inspection.dto.SpcDtos.ChartType;
import com.qualityos.inspection.dto.SpcDtos.Point;
import com.qualityos.inspection.service.SpcCalculator.Result;
import com.qualityos.inspection.service.SpcCalculator.Spec;
import com.qualityos.inspection.service.SpcCalculator.Subgroup;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpcCalculatorTest {

    private static final Spec FIVE_TO_SEVENTEEN = new Spec(5.0, 17.0);

    private static List<Subgroup> subgroups(double[]... values) {
        List<Subgroup> subgroups = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            List<Double> samples = new ArrayList<>();
            for (double value : values[i]) {
                samples.add(value);
            }
            subgroups.add(new Subgroup(UUID.randomUUID(), "INS-" + i, "B-" + i, Instant.EPOCH.plusSeconds(i), samples));
        }
        return subgroups;
    }

    @Test
    void xbarRTakesTheMeanAndRangeOfEachInspection() {
        Result result = SpcCalculator.calculate(ChartType.XBAR_R, subgroups(
                new double[] {10, 11, 12}, new double[] {11, 12, 13}, new double[] {9, 10, 11},
                new double[] {10, 12, 14}), FIVE_TO_SEVENTEEN);

        assertThat(result.subgroupSize()).isEqualTo(3);
        assertThat(result.points()).extracting(Point::value).containsExactly(11.0, 12.0, 10.0, 12.0);
        assertThat(result.points()).extracting(Point::range).containsExactly(2.0, 2.0, 2.0, 4.0);
        // X̿ 11.25, R̄ 2.5, A2 1.023, D3 0, D4 2.574.
        assertThat(result.valueLimits().centre()).isCloseTo(11.25, within(1e-9));
        assertThat(result.valueLimits().upper()).isCloseTo(13.8075, within(1e-9));
        assertThat(result.valueLimits().lower()).isCloseTo(8.6925, within(1e-9));
        assertThat(result.rangeLimits().centre()).isCloseTo(2.5, within(1e-9));
        assertThat(result.rangeLimits().upper()).isCloseTo(6.435, within(1e-9));
        assertThat(result.rangeLimits().lower()).isZero();
        // σ within R̄ / d2 = 2.5 / 1.693; overall the sample standard deviation of all twelve.
        var capability = result.capability();
        assertThat(capability.measurements()).isEqualTo(12);
        assertThat(capability.mean()).isCloseTo(11.25, within(1e-9));
        assertThat(capability.sigmaWithin()).isCloseTo(1.476668, within(1e-6));
        assertThat(capability.cp()).isCloseTo(12 / (6 * 2.5 / 1.693), within(1e-9));
        assertThat(capability.cpk()).isCloseTo(5.75 / (3 * 2.5 / 1.693), within(1e-9));
        assertThat(capability.sigmaOverall()).isCloseTo(Math.sqrt(22.25 / 11), within(1e-9));
        assertThat(capability.pp()).isCloseTo(12 / (6 * Math.sqrt(22.25 / 11)), within(1e-9));
        assertThat(capability.ppk()).isCloseTo(5.75 / (3 * Math.sqrt(22.25 / 11)), within(1e-9));
        assertThat(result.preliminary()).isTrue();
        assertThat(result.points()).noneMatch(Point::outOfControl);
    }

    @Test
    void xbarRLeavesOutInspectionsWithAnotherNumberOfSamples() {
        Result result = SpcCalculator.calculate(ChartType.XBAR_R, subgroups(
                new double[] {10, 11, 12}, new double[] {11, 12}, new double[] {9, 10, 11}), FIVE_TO_SEVENTEEN);
        assertThat(result.subgroupSize()).isEqualTo(3);
        assertThat(result.excludedSubgroups()).isEqualTo(1);
        assertThat(result.points()).hasSize(2);
    }

    @Test
    void individualsTakeEachMeasurementWithTheMovingRange() {
        Result result = SpcCalculator.calculate(ChartType.I_MR, subgroups(
                new double[] {5}, new double[] {7}, new double[] {6, 8}), FIVE_TO_SEVENTEEN);

        assertThat(result.points()).extracting(Point::value).containsExactly(5.0, 7.0, 6.0, 8.0);
        assertThat(result.points()).extracting(Point::range).containsExactly(null, 2.0, 1.0, 2.0);
        double meanMovingRange = 5.0 / 3;
        assertThat(result.valueLimits().centre()).isCloseTo(6.5, within(1e-9));
        assertThat(result.valueLimits().upper()).isCloseTo(6.5 + 3 * meanMovingRange / 1.128, within(1e-9));
        assertThat(result.valueLimits().lower()).isCloseTo(6.5 - 3 * meanMovingRange / 1.128, within(1e-9));
        assertThat(result.rangeLimits().upper()).isCloseTo(3.267 * meanMovingRange, within(1e-9));
        assertThat(result.rangeLimits().lower()).isNull();
        assertThat(result.capability().sigmaWithin()).isCloseTo(meanMovingRange / 1.128, within(1e-9));
    }

    @Test
    void marksPointsBeyondTheControlLimits() {
        Result result = SpcCalculator.calculate(ChartType.I_MR, subgroups(new double[] {10}, new double[] {10.2},
                new double[] {9.9}, new double[] {10.1}, new double[] {10}, new double[] {9.8},
                new double[] {10.1}, new double[] {10}, new double[] {14}), new Spec(9.0, 11.0));
        assertThat(result.points()).extracting(Point::outOfControl)
                .containsExactly(false, false, false, false, false, false, false, false, true);
        assertThat(result.points().getLast().rangeOutOfControl()).isTrue();
    }

    @Test
    void aOneSidedSpecificationHasCpkButNoCp() {
        Result result = SpcCalculator.calculate(ChartType.I_MR, subgroups(new double[] {5}, new double[] {7},
                new double[] {6}, new double[] {8}), new Spec(null, 12.0));
        double sigma = (5.0 / 3) / 1.128;
        assertThat(result.capability().cp()).isNull();
        assertThat(result.capability().pp()).isNull();
        assertThat(result.capability().cpk()).isCloseTo((12 - 6.5) / (3 * sigma), within(1e-9));
    }

    @Test
    void noVariationOrNoSpecificationLeavesCapabilityOpen() {
        Result flat = SpcCalculator.calculate(ChartType.I_MR, subgroups(new double[] {5}, new double[] {5}),
                FIVE_TO_SEVENTEEN);
        assertThat(flat.capability().sigmaWithin()).isNull();
        assertThat(flat.capability().cpk()).isNull();
        Result unspecified = SpcCalculator.calculate(ChartType.I_MR, subgroups(new double[] {5}, new double[] {6}),
                new Spec(null, null));
        assertThat(unspecified.capability().cp()).isNull();
        assertThat(unspecified.capability().cpk()).isNull();
        assertThat(unspecified.capability().mean()).isCloseTo(5.5, within(1e-9));
    }

    @Test
    void suggestsTheChartTheSamplesSuit() {
        assertThat(SpcCalculator.suitedTo(subgroups(new double[] {1}, new double[] {2}))).isEqualTo(ChartType.I_MR);
        assertThat(SpcCalculator.suitedTo(subgroups(new double[] {1, 2, 3, 4, 5}))).isEqualTo(ChartType.XBAR_R);
        assertThat(SpcCalculator.suitedTo(subgroups(new double[12]))).isEqualTo(ChartType.I_MR);
    }

    @Test
    void refusesWhatTheChartCannotShow() {
        assertThatThrownBy(() -> SpcCalculator.calculate(ChartType.XBAR_R, subgroups(new double[] {1},
                new double[] {2}), FIVE_TO_SEVENTEEN)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("individuals chart");
        assertThatThrownBy(() -> SpcCalculator.calculate(ChartType.XBAR_R, subgroups(new double[12], new double[12]),
                FIVE_TO_SEVENTEEN)).hasMessageContaining("2 to 10 samples");
        assertThatThrownBy(() -> SpcCalculator.calculate(ChartType.XBAR_R, subgroups(new double[] {1, 2}),
                FIVE_TO_SEVENTEEN)).hasMessageContaining("at least two inspections");
        assertThatThrownBy(() -> SpcCalculator.calculate(ChartType.I_MR, subgroups(new double[] {1}),
                FIVE_TO_SEVENTEEN)).hasMessageContaining("at least two measurements");
    }
}
