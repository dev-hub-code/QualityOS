package com.qualityos.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EnumLabelsTest {

    @Test
    void wordsAStoredValueAndKeepsAcronymsAndOtherText() {
        assertThat(EnumLabels.label("CONDITIONAL_ACCEPTANCE")).isEqualTo("Conditional acceptance");
        assertThat(EnumLabels.label("SUPPLIER")).isEqualTo("Supplier");
        assertThat(EnumLabels.label("FQC")).isEqualTo("FQC");
        assertThat(EnumLabels.label("PB-2026-00001")).isEqualTo("PB-2026-00001");
        assertThat(EnumLabels.label(null)).isNull();
    }
}
