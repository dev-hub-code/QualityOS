package com.qualityos.inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qualityos.common.exception.BusinessRuleException;
import com.qualityos.inspection.entity.AqlTable;
import com.qualityos.inspection.entity.AqlTable.Level;
import com.qualityos.inspection.entity.AqlTable.Plan;
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
        assertThat(plan(40_000, Level.I, "1.0")).isEqualTo(new Plan('L', 200, 5));
        assertThat(plan(40_000, Level.II, "1.0")).isEqualTo(new Plan('N', 500, 10));
        assertThat(plan(40_000, Level.III, "1.0")).isEqualTo(new Plan('P', 800, 14));
        assertThat(plan(500, Level.II, "1.0").codeLetter()).isEqualTo('H');
    }

    @Test
    void acceptanceNumbersMatchTheMasterTable() {
        assertThat(plan(1_200, Level.II, "1.0")).isEqualTo(new Plan('J', 80, 2));
        assertThat(plan(3_000, Level.II, "2.5")).isEqualTo(new Plan('K', 125, 7));
        assertThat(plan(10_000, Level.II, "1.5")).isEqualTo(new Plan('L', 200, 7));
        assertThat(plan(10_000, Level.II, "2.5")).isEqualTo(new Plan('L', 200, 10));
        assertThat(plan(1_200, Level.II, "0.65")).isEqualTo(new Plan('J', 80, 1));
        assertThat(plan(300, Level.II, "4.0")).isEqualTo(new Plan('H', 50, 5));
    }

    @Test
    void arrowsLeadToTheNeighbouringPlanAndItsSampleSize() {
        // H at 0.25 accepts nothing; at 0.40 the arrow points up to it, at 0.65 down to J accepting one.
        assertThat(plan(500, Level.II, "0.25")).isEqualTo(new Plan('H', 50, 0));
        assertThat(plan(500, Level.II, "0.40")).isEqualTo(new Plan('G', 32, 0));
        assertThat(plan(500, Level.II, "0.65")).isEqualTo(new Plan('J', 80, 1));
        // A tight AQL on a small lot goes down to its first plan, and a loose one up to the plan accepting 21.
        assertThat(plan(1_200, Level.II, "0.10")).isEqualTo(new Plan('K', 125, 0));
        assertThat(plan(35_000, Level.II, "10")).isEqualTo(new Plan('K', 125, 21));
    }

    @Test
    void aSampleAsLargeAsTheLotInspectsEveryUnit() {
        assertThat(plan(5, Level.II, "0.10")).isEqualTo(new Plan('K', 5, 0));
        assertThat(plan(1, Level.II, "6.5")).isEqualTo(new Plan('A', 1, 0));
    }

    @Test
    void onlyTheTablesAqlsAreAccepted() {
        assertThatThrownBy(() -> plan(100, Level.II, "2.0")).isInstanceOf(BusinessRuleException.class);
        assertThat(plan(100, Level.II, "2.50").acceptanceNumber()).isEqualTo(plan(100, Level.II, "2.5").acceptanceNumber());
    }
}
