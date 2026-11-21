package com.qualityos.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.reporting.dto.SpcDtos.ChartType;
import com.qualityos.reporting.dto.SpcDtos.Point;
import com.qualityos.reporting.dto.SpcDtos.Signal;
import com.qualityos.reporting.service.SpcMath.Reading;
import com.qualityos.reporting.service.SpcMath.Result;
import com.qualityos.reporting.service.SpcMath.Spec;
import com.qualityos.reporting.service.SpcMath.Subgroup;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Expected figures were worked out separately in Python, from the textbook formulas, to 9 significant figures. */
class SpcMathTest {

    private static final Spec NO_SPEC = new Spec(null, null);

    private static Subgroup subgroup(double... values) {
        List<Reading> readings = IntStream.range(0, values.length).mapToObj(i -> new Reading(i + 1, values[i])).toList();
        return new Subgroup(UUID.randomUUID(), "INS", "B", Instant.EPOCH, readings);
    }

    private static void near(double actual, double expected) {
        assertThat(actual).isCloseTo(expected, within(Math.abs(expected) * 1e-12 + 1e-15));
    }

    @Test
    void equalSubgroupsGiveTheTextbookLimits() {
        Result result = SpcMath.xbarR(List.of(
                subgroup(10.1, 10.3, 9.9, 10.0, 10.2), subgroup(10.0, 10.4, 10.1, 9.8, 10.2),
                subgroup(9.9, 10.1, 10.0, 10.2, 10.3), subgroup(10.2, 10.0, 9.7, 10.1, 10.0),
                subgroup(10.1, 10.2, 10.3, 9.9, 10.0), subgroup(10.0, 9.8, 10.1, 10.2, 10.4)), NO_SPEC);
        assertThat(result.chartType()).isEqualTo(ChartType.XBAR_R);
        near(result.centre(), 10.0833333);
        near(result.sigma(), 0.20779593);
        // X̿ ± A2·R̄, and D3·R̄ to D4·R̄ with D3 = 0 for n = 5.
        for (Point point : result.points()) {
            near(point.ucl(), 10.3621208);
            near(point.lcl(), 9.80454584);
            near(point.rangeCentre(), 0.483333333);
            near(point.rangeUcl(), 1.02194038);
            near(point.rangeLcl(), 0);
            assertThat(point.n()).isEqualTo(5);
        }
    }

    @Test
    void mixedSizesGiveEachPointItsOwnLimits() {
        Result result = SpcMath.xbarR(List.of(subgroup(5.0, 5.2, 4.9), subgroup(5.1, 5.0, 5.3, 4.8, 5.0),
                subgroup(4.9, 5.1, 5.0, 5.2, 5.1, 4.8, 5.0, 5.3)), NO_SPEC);
        // σ̂ is the average of Rᵢ / d₂(nᵢ), and X̿ weights each mean by its n.
        near(result.sigma(), 0.189261669);
        near(result.centre(), 5.04375);
        double[][] expected = {
            {5.37156083, 4.71593917, 0.320420005, 0.824613091, 0},
            {5.29767117, 4.78982883, 0.440222642, 0.930788887, 0},
            {5.24449231, 4.84300769, 0.538827971, 1.00441168, 0.0732442658},
        };
        for (int i = 0; i < 3; i++) {
            Point point = result.points().get(i);
            near(point.ucl(), expected[i][0]);
            near(point.lcl(), expected[i][1]);
            near(point.rangeCentre(), expected[i][2]);
            near(point.rangeUcl(), expected[i][3]);
            near(point.rangeLcl(), expected[i][4]);
        }
    }

    @Test
    void individualsUseTheMovingRange() {
        Result result = SpcMath.individuals(List.of(subgroup(2.0, 2.3, 2.1), subgroup(2.4, 2.2), subgroup(2.0, 2.5)),
                new Spec(1.5, 3.0));
        assertThat(result.chartType()).isEqualTo(ChartType.I_MR);
        assertThat(result.points()).hasSize(7);
        Point first = result.points().getFirst();
        assertThat(first.range()).isNull();
        assertThat(first.sampleNumber()).isEqualTo(1);
        assertThat(result.points().get(3).sampleNumber()).isEqualTo(1);
        near(result.points().get(1).range(), 0.3);
        near(result.centre(), 2.21428571);
        near(result.sigma(), 0.251182033);
        near(first.ucl(), 2.96795238);
        near(first.lcl(), 1.46061905);
        near(first.rangeCentre(), 0.283333333);
        near(first.rangeUcl(), 0.92565);
        near(first.rangeLcl(), 0);
        near(result.capability().cp(), 0.995294118);
        near(result.capability().cpk(), 0.94789916);
    }

    @Test
    void capabilityWithOneLimitNoneOrNoVariation() {
        List<Subgroup> readings = List.of(subgroup(2.0, 2.3, 2.1, 2.4, 2.2, 2.0, 2.5));
        Result upper = SpcMath.individuals(readings, new Spec(null, 3.0));
        assertThat(upper.capability().cp()).isNull();
        near(upper.capability().cpk(), 1.04268908);
        Result lower = SpcMath.individuals(readings, new Spec(1.5, null));
        assertThat(lower.capability().cp()).isNull();
        near(lower.capability().cpk(), 0.94789916);

        Result none = SpcMath.individuals(readings, NO_SPEC);
        assertThat(none.capability().cp()).isNull();
        assertThat(none.capability().cpk()).isNull();
        assertThat(none.warnings()).anyMatch(warning -> warning.contains("no specification limits"));

        Result flat = SpcMath.xbarR(List.of(subgroup(5, 5), subgroup(5, 5)), new Spec(4.0, 6.0));
        assertThat(flat.sigma()).isZero();
        assertThat(flat.capability().cpk()).isNull();
        assertThat(flat.warnings()).anyMatch(warning -> warning.contains("do not vary"));
        assertThat(flat.points()).allMatch(point -> point.signals().isEmpty());
        assertThat(flat.warnings()).anyMatch(warning -> warning.startsWith("Fewer than 25 subgroups"));
    }

    @Test
    void marksPointsBeyondTheirLimits() {
        List<Subgroup> subgroups = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            subgroups.add(i % 2 == 0 ? subgroup(10.0, 10.1, 9.9) : subgroup(9.95, 10.05, 10.0));
        }
        subgroups.set(6, subgroup(10.6, 10.7, 10.5));
        subgroups.set(9, subgroup(9.0, 11.0, 10.0));
        List<Point> points = SpcMath.xbarR(subgroups, NO_SPEC).points();
        assertThat(points.get(6).signals()).containsExactly(Signal.BEYOND_LIMITS);
        assertThat(points.get(9).signals()).containsExactly(Signal.RANGE_BEYOND_LIMITS);
        assertThat(points.get(0).signals()).isEmpty();
    }

    @Test
    void flagsEveryPointInARunOfEightAndTheCentreLineBreaksIt() {
        double[] values = {1, 1, 1, 1, 1, 1, 1, 1, -1, 1, 1, 1, 1, 0, 1, 1, 1};
        boolean[] run = SpcMath.runs(values, 0);
        for (int i = 0; i < 8; i++) {
            assertThat(run[i]).as("point %d", i).isTrue();
        }
        for (int i = 8; i < values.length; i++) {
            assertThat(run[i]).as("point %d", i).isFalse();
        }
        // Seven above, one on the line, seven above: no run.
        assertThat(SpcMath.runs(new double[] {1, 1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1}, 0)).doesNotContain(true);
        // On a chart: readings alternate across the centre (1.47), then the last 9 sit above it.
        List<Subgroup> readings = List.of(subgroup(3, -1, 3, -1, 3, -1, 3, -1, 3, -1, 2, 2, 2, 2, 2, 2, 2, 2, 2));
        List<Point> points = SpcMath.individuals(readings, NO_SPEC).points();
        assertThat(points.subList(10, 19)).allMatch(point -> point.signals().contains(Signal.RUN_OF_8));
        assertThat(points.subList(0, 10)).noneMatch(point -> point.signals().contains(Signal.RUN_OF_8));
    }

    @Test
    void autoFollowsTheReadingsAndExclusionsAreCounted() {
        // X̄-R needs at least half the subgroups, and at least two, with 2 to 25 readings.
        double[] large = IntStream.range(0, 26).mapToDouble(i -> i % 3).toArray();
        assertThat(SpcMath.auto(List.of(subgroup(1, 2), subgroup(3, 4), subgroup(1), subgroup(2))))
                .isEqualTo(ChartType.XBAR_R);
        assertThat(SpcMath.auto(List.of(subgroup(1, 2), subgroup(1)))).isEqualTo(ChartType.I_MR);
        assertThat(SpcMath.auto(List.of(subgroup(1, 2), subgroup(1), subgroup(2)))).isEqualTo(ChartType.I_MR);
        assertThat(SpcMath.auto(List.of(subgroup(1, 2), subgroup(large), subgroup(large)))).isEqualTo(ChartType.I_MR);

        Result result = SpcMath.xbarR(List.of(subgroup(1, 2), subgroup(1), subgroup(2, 3, 4), subgroup(large)),
                NO_SPEC);
        assertThat(result.points()).hasSize(2);
        assertThat(result.excluded().singleReading()).isEqualTo(1);
        assertThat(result.excluded().tooLarge()).isEqualTo(1);
    }

    @Test
    void tooFewToChartSaysTheMinimum() {
        assertThatThrownBy(() -> SpcMath.xbarR(List.of(subgroup(1, 2), subgroup(3)), NO_SPEC))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("at least 2 inspections");
        assertThatThrownBy(() -> SpcMath.individuals(List.of(subgroup(1)), NO_SPEC))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("at least 2 readings");
    }

    @Test
    void roundsToNineSignificantFigures() {
        // Six stored decimals survive, and floating-point noise does not.
        assertThat(SpcMath.round(20.123456)).isEqualTo(20.123456);
        assertThat(SpcMath.round(0.1 + 0.2)).isEqualTo(0.3);
        assertThat(SpcMath.round(20.00666666666)).isEqualTo(20.0066667);
        assertThat(SpcMath.round(0)).isZero();
    }
}
