package com.qualityos.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class GstBreakupTest {

    private static final String MAHARASHTRA = "27AAAAA0000A1Z5";
    private static final String KARNATAKA = "29AAAAA0000A1Z5";

    @Test
    void theSameStateSplitsIntoCgstAndSgstThatAddUp() {
        GstBreakup split = GstBreakup.of(MAHARASHTRA, MAHARASHTRA, new BigDecimal("100.01"));

        assertThat(split.treatment()).isEqualTo("INTRA_STATE");
        assertThat(split.cgst().add(split.sgst())).isEqualByComparingTo("100.01");
        assertThat(split.igst()).isNull();
        assertThat(split.placeOfSupply()).isEqualTo("27");
    }

    @Test
    void anotherStateIsIgst() {
        GstBreakup split = GstBreakup.of(MAHARASHTRA, KARNATAKA, new BigDecimal("180.00"));

        assertThat(split.treatment()).isEqualTo("INTER_STATE");
        assertThat(split.igst()).isEqualByComparingTo("180.00");
        assertThat(split.cgst()).isNull();
        assertThat(split.placeOfSupply()).isEqualTo("29");
    }

    @Test
    void aMissingOrMalformedGstinIsNotGuessedAt() {
        assertThat(GstBreakup.of(MAHARASHTRA, null, BigDecimal.TEN).treatment()).isEqualTo("UNDETERMINED");
        assertThat(GstBreakup.of("", MAHARASHTRA, BigDecimal.TEN).treatment()).isEqualTo("UNDETERMINED");
        assertThat(GstBreakup.of("XX", MAHARASHTRA, BigDecimal.TEN).igst()).isNull();
    }
}
