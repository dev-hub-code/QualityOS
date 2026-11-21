package com.qualityos.organization.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FacilityDaysOffTest {

    private static final int SATURDAY = 6;
    private static final int SECOND_AND_FOURTH = 2 | 8;

    @Test
    void theSecondAndFourthSaturdayAreOffAndTheOthersAreWorked() {
        // November 2026: Saturdays on the 7th, 14th, 21st and 28th.
        assertThat(FacilityService.isDayOff(LocalDate.of(2026, 11, 7), 0, SATURDAY, SECOND_AND_FOURTH)).isFalse();
        assertThat(FacilityService.isDayOff(LocalDate.of(2026, 11, 14), 0, SATURDAY, SECOND_AND_FOURTH)).isTrue();
        assertThat(FacilityService.isDayOff(LocalDate.of(2026, 11, 21), 0, SATURDAY, SECOND_AND_FOURTH)).isFalse();
        assertThat(FacilityService.isDayOff(LocalDate.of(2026, 11, 28), 0, SATURDAY, SECOND_AND_FOURTH)).isTrue();
        assertThat(FacilityService.isDayOff(LocalDate.of(2026, 11, 13), 0, SATURDAY, SECOND_AND_FOURTH)).isFalse();
    }

    @Test
    void aFifthOccurrenceCountsOnlyWhenChosen() {
        // August 2026 has five Saturdays; the 29th is the fifth.
        LocalDate fifth = LocalDate.of(2026, 8, 29);
        assertThat(FacilityService.isDayOff(fifth, 0, SATURDAY, SECOND_AND_FOURTH)).isFalse();
        assertThat(FacilityService.isDayOff(fifth, 0, SATURDAY, 1 | 4 | 16)).isTrue();
    }

    @Test
    void aWeeklyOffIsOffEveryWeek() {
        int sunday = 64;
        assertThat(FacilityService.isDayOff(LocalDate.of(2026, 11, 22), sunday, null, 0)).isTrue();
        assertThat(FacilityService.isDayOff(LocalDate.of(2026, 11, 23), sunday, null, 0)).isFalse();
    }
}
