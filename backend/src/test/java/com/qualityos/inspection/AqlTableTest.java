package com.qualityos.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.inspection.entity.AqlTable;
import com.qualityos.inspection.entity.AqlTable.Level;
import com.qualityos.inspection.entity.AqlTable.Plan;
import com.qualityos.inspection.entity.AqlTable.Severity;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AqlTableTest {

    private static Plan plan(long lot, Level level, String aql) {
        return AqlTable.plan(lot, level, new BigDecimal(aql));
    }

    @Test
    void codeLettersFollowLotSizeAndLevel() {
        // Published examples: 5,000 units take 80, 200 and 315 samples; 40,000 take 200, 500 and 800.
        assertThat(plan(5_000, Level.I, "6.5").sampleSize()).isEqualTo(80);
        assertThat(plan(5_000, Level.II, "6.5").sampleSize()).isEqualTo(200);
        assertThat(plan(5_000, Level.III, "4.0").sampleSize()).isEqualTo(315);
        assertThat(plan(40_000, Level.I, "1.0")).isEqualTo(new Plan('L', 200, 5, 6));
        assertThat(plan(40_000, Level.II, "1.0")).isEqualTo(new Plan('N', 500, 10, 11));
        assertThat(plan(40_000, Level.III, "1.0")).isEqualTo(new Plan('P', 800, 14, 15));
        assertThat(plan(500, Level.II, "1.0").codeLetter()).isEqualTo('H');
    }

    @Test
    void acceptanceNumbersMatchTheMasterTable() {
        assertThat(plan(1_200, Level.II, "1.0")).isEqualTo(new Plan('J', 80, 2, 3));
        assertThat(plan(3_000, Level.II, "2.5")).isEqualTo(new Plan('K', 125, 7, 8));
        assertThat(plan(10_000, Level.II, "1.5")).isEqualTo(new Plan('L', 200, 7, 8));
        assertThat(plan(10_000, Level.II, "2.5")).isEqualTo(new Plan('L', 200, 10, 11));
        assertThat(plan(1_200, Level.II, "0.65")).isEqualTo(new Plan('J', 80, 1, 2));
        assertThat(plan(300, Level.II, "4.0")).isEqualTo(new Plan('H', 50, 5, 6));
    }

    @Test
    void arrowsLeadToTheNeighbouringPlanAndItsSampleSize() {
        // H at 0.25 accepts nothing; at 0.40 the arrow points up to G's plan, at 0.65 down to J's accepting one.
        assertThat(plan(500, Level.II, "0.25")).isEqualTo(new Plan('H', 50, 0, 1));
        assertThat(plan(500, Level.II, "0.40")).isEqualTo(new Plan('H', 32, 0, 1));
        assertThat(plan(500, Level.II, "0.65")).isEqualTo(new Plan('H', 80, 1, 2));
        // A tight AQL on a small lot goes down to its first plan, and a loose one up to the plan accepting 21.
        assertThat(plan(1_200, Level.II, "0.10")).isEqualTo(new Plan('J', 125, 0, 1));
        assertThat(plan(35_000, Level.II, "10")).isEqualTo(new Plan('M', 125, 21, 22));
    }

    @Test
    void aSampleAsLargeAsTheLotInspectsEveryUnit() {
        assertThat(plan(5, Level.II, "0.10")).isEqualTo(new Plan('A', 5, 0, 1));
        assertThat(plan(1, Level.II, "6.5")).isEqualTo(new Plan('A', 1, 0, 1));
    }

    private static Plan plan(long lot, Level level, String aql, Severity severity) {
        return AqlTable.plan(lot, level, new BigDecimal(aql), severity);
    }

    @Test
    void tightenedInspectionKeepsTheSampleAndLowersAc() {
        // K at 1.0: normal 125 accepting 3, tightened 125 accepting 2.
        assertThat(plan(3_000, Level.II, "1.0", Severity.TIGHTENED)).isEqualTo(new Plan('K', 125, 2, 3));
        assertThat(plan(10_000, Level.II, "2.5", Severity.TIGHTENED)).isEqualTo(new Plan('L', 200, 8, 9));
        assertThat(plan(35_000, Level.II, "2.5", Severity.TIGHTENED)).isEqualTo(new Plan('M', 315, 12, 13));
        assertThat(plan(35_000, Level.II, "4.0", Severity.TIGHTENED)).isEqualTo(new Plan('M', 315, 18, 19));
        // M at 6.5 points up to L's plan, which accepts the most, 18.
        assertThat(plan(35_000, Level.II, "6.5", Severity.TIGHTENED)).isEqualTo(new Plan('M', 200, 18, 19));
        // Two arrows down from G's zero: H at 0.65 takes K's plan, 125 accepting 1.
        assertThat(plan(500, Level.II, "0.65", Severity.TIGHTENED)).isEqualTo(new Plan('H', 125, 1, 2));
        // Only tightened inspection reaches letter S, 3,150 units, for the largest lots at 0.025.
        assertThat(AqlTable.plan(600_000, Level.II, new BigDecimal("0.025"), Severity.TIGHTENED).sampleSize())
                .isEqualTo(3_150);
    }

    @Test
    void reducedInspectionTakesASmallerSampleWithAGap() {
        // K at 1.0 reduced: 50 units, accept 1, reject from 4. Two or three is accepted, back on normal.
        assertThat(plan(3_000, Level.II, "1.0", Severity.REDUCED)).isEqualTo(new Plan('K', 50, 1, 4));
        assertThat(plan(10_000, Level.II, "0.65", Severity.REDUCED)).isEqualTo(new Plan('L', 80, 1, 4));
        assertThat(plan(500, Level.II, "0.65", Severity.REDUCED)).isEqualTo(new Plan('H', 32, 0, 2));
    }

    @Test
    void specialLevelsGiveSmallSamples() {
        assertThat(plan(5_000, Level.S1, "4.0").codeLetter()).isEqualTo('C');
        assertThat(plan(5_000, Level.S2, "4.0").codeLetter()).isEqualTo('D');
        assertThat(plan(5_000, Level.S3, "4.0").codeLetter()).isEqualTo('F');
        assertThat(plan(5_000, Level.S4, "4.0")).isEqualTo(new Plan('G', 32, 3, 4));
        assertThat(plan(1_000_000, Level.S4, "1.0")).isEqualTo(new Plan('K', 125, 3, 4));
        assertThat(Level.S3.label()).isEqualTo("S-3");
    }

    @Test
    void theEdgesOfTheTableHaveAPlan() {
        // A small lot at 10 and a huge lot at 0.015 point off the table's edge; the published plans cover them.
        assertThat(plan(8, Level.II, "10")).isEqualTo(new Plan('A', 5, 1, 2));
        assertThat(plan(600_000, Level.III, "0.015")).isEqualTo(new Plan('R', 800, 0, 1));
        for (Severity severity : Severity.values()) {
            for (BigDecimal aql : AqlTable.AQLS) {
                for (long lot : new long[] {2, 9, 16, 26, 51, 91, 151, 281, 501, 1_201, 3_201, 10_001, 35_001,
                        150_001, 500_001}) {
                    for (Level level : Level.values()) {
                        Plan found = AqlTable.plan(lot, level, aql, severity);
                        assertThat(found.rejectionNumber()).isGreaterThan(found.acceptanceNumber());
                        assertThat(found.sampleSize()).isPositive();
                    }
                }
            }
        }
    }

    @Test
    void onlyTheTablesAqlsAreAccepted() {
        assertThatThrownBy(() -> plan(100, Level.II, "2.0")).isInstanceOf(BusinessRuleException.class);
        assertThat(plan(100, Level.II, "2.50").acceptanceNumber()).isEqualTo(plan(100, Level.II, "2.5").acceptanceNumber());
    }

    private static java.util.Optional<AqlTable.DoublePlan> twice(long lot, Level level, String aql,
            AqlTable.Severity severity) {
        return AqlTable.doublePlan(lot, level, new BigDecimal(aql), severity);
    }

    @Test
    void doubleSamplingFollowsTablesIIIAToIIIC() {
        // Published example: a lot of 2,000 at level II and AQL 0.65 is letter K; two samples of 80, accepting 0 and
        // rejecting from 3 on the first, then accepting 3 and rejecting from 4 on both.
        assertThat(twice(2_000, Level.II, "0.65", AqlTable.Severity.NORMAL))
                .contains(new AqlTable.DoublePlan('K', 80, 0, 3, 3, 4));
        assertThat(twice(2_000, Level.II, "1.0", AqlTable.Severity.NORMAL))
                .contains(new AqlTable.DoublePlan('K', 80, 1, 4, 4, 5));
        assertThat(twice(10_000, Level.II, "2.5", AqlTable.Severity.NORMAL))
                .contains(new AqlTable.DoublePlan('L', 125, 5, 9, 12, 13));
        // Tightened keeps the samples and lowers the numbers; reduced takes smaller samples, with a gap after both.
        assertThat(twice(2_000, Level.II, "1.0", AqlTable.Severity.TIGHTENED))
                .contains(new AqlTable.DoublePlan('K', 80, 0, 3, 3, 4));
        assertThat(twice(2_000, Level.II, "4.0", AqlTable.Severity.TIGHTENED))
                .contains(new AqlTable.DoublePlan('K', 80, 3, 7, 11, 12));
        assertThat(twice(2_000, Level.II, "1.0", AqlTable.Severity.REDUCED))
                .contains(new AqlTable.DoublePlan('K', 32, 0, 4, 1, 5));
        assertThat(twice(2_000, Level.II, "4.0", AqlTable.Severity.REDUCED))
                .contains(new AqlTable.DoublePlan('K', 32, 2, 7, 6, 9));
    }

    @Test
    void doubleSamplingFallsBackToSingleWhereTheTableHasNoPlanOrTheLotIsTooSmall() {
        // Where single sampling accepts none, the standard gives no double plan.
        assertThat(twice(2_000, Level.II, "0.10", AqlTable.Severity.NORMAL)).isEmpty();
        // Nine units at level III are letter C, whose two samples of 5 need ten.
        assertThat(twice(9, Level.III, "6.5", AqlTable.Severity.NORMAL)).isEmpty();
        assertThat(twice(15, Level.III, "6.5", AqlTable.Severity.NORMAL))
                .contains(new AqlTable.DoublePlan('C', 5, 0, 2, 1, 2));
    }
}
