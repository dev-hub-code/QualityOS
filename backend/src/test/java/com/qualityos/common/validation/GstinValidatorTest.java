package com.qualityos.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GstinValidatorTest {

    @Test
    void acceptsAGstinWhoseCheckCharacterIsRight() {
        assertThat(GstinValidator.valid("27AAPFU0939F1ZV")).isTrue();
        assertThat(GstinValidator.valid("27AAKCD4321M1ZG")).isTrue();
    }

    @Test
    void refusesAWrongCheckCharacterOrShape() {
        assertThat(GstinValidator.valid("27AAKCD4321M1Z9")).isFalse();
        assertThat(GstinValidator.valid("27AAPFU0939F1XV")).isFalse();
        assertThat(GstinValidator.valid("ABC")).isFalse();
    }

    @Test
    void blankPassesSinceTheNumberIsOptional() {
        assertThat(new GstinValidator().isValid("", null)).isTrue();
        assertThat(new GstinValidator().isValid(null, null)).isTrue();
    }
}
