package com.qualityos.production.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.qualityos.production.dto.ProductionAnalyticsDtos.MachineOee;
import org.junit.jupiter.api.Test;

/** OEE worked by hand, as in docs/AUDIT_MANUFACTURING_2026-11-17.md. */
class OeeFormulaTest {

    @Test
    void oeeIsTheProductOfTheUnroundedFactors() {
        // A 450-minute shift with 30 planned down: 420 planned. It ran 360 and made 50, 48 good, at 6 minutes ideal.
        MachineOee oee = ProductionAnalyticsService.oee(null, "M-1", "WC-1", 450, 30, 360, 0, 60,
                new double[] {50, 48, 300});
        assertThat(oee.availability()).isEqualByComparingTo("85.7");
        assertThat(oee.performance()).isEqualByComparingTo("83.3");
        assertThat(oee.quality()).isEqualByComparingTo("96.0");
        // 6/7 × 5/6 × 0.96 = 68.57%; multiplying the rounded factors would give 68.5.
        assertThat(oee.oee()).isEqualByComparingTo("68.6");
        assertThat(oee.utilisation()).isEqualByComparingTo("80.0");
    }

    @Test
    void runningPastThePlannedTimeCapsAvailabilityAndSaysSo() {
        MachineOee oee = ProductionAnalyticsService.oee(null, "M-1", "WC-1", 480, 0, 500, 0, 0,
                new double[] {100, 100, 500});
        assertThat(oee.availability()).isEqualByComparingTo("100.0");
        assertThat(oee.oee()).isEqualByComparingTo("100.0");
        assertThat(oee.notes()).anyMatch(note -> note.contains("availability is shown as 100%"));
    }

    @Test
    void aFigureWithoutItsDataIsLeftOutNotZero() {
        MachineOee idle = ProductionAnalyticsService.oee(null, "M-1", "WC-1", 480, 0, 0, 0, 0, new double[3]);
        assertThat(idle.availability()).isEqualByComparingTo("0.0");
        assertThat(idle.performance()).isNull();
        assertThat(idle.quality()).isNull();
        assertThat(idle.oee()).isNull();
    }

    @Test
    void setupIsLostAvailabilityNotSlowRunning() {
        // As in the first case, but 60 of the 360 minutes it ran were setup: it made things for 300.
        MachineOee oee = ProductionAnalyticsService.oee(null, "M-1", "WC-1", 450, 30, 360, 60, 60,
                new double[] {50, 48, 300});
        assertThat(oee.availability()).isEqualByComparingTo("71.4"); // 300 / 420
        assertThat(oee.performance()).isEqualByComparingTo("100.0"); // 300 ideal in 300
        assertThat(oee.quality()).isEqualByComparingTo("96.0");
        // The product is the same as counting setup as running: only where the loss shows changes.
        assertThat(oee.oee()).isEqualByComparingTo("68.6");
        assertThat(oee.utilisation()).isEqualByComparingTo("80.0"); // setup still occupies the machine
        assertThat(oee.setupMinutes()).isEqualByComparingTo("60");
    }

    @Test
    void aMachineThatOnlySetUpHasNoPerformance() {
        MachineOee oee = ProductionAnalyticsService.oee(null, "M-1", "WC-1", 480, 0, 30, 30, 0, new double[3]);
        assertThat(oee.availability()).isEqualByComparingTo("0.0");
        assertThat(oee.performance()).isNull();
        assertThat(oee.notes()).anyMatch(note -> note.startsWith("All its running time was setup"));
    }
}
