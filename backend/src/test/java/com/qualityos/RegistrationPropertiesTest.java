package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;

import com.qualityos.identity.service.RegistrationProperties;
import org.junit.jupiter.api.Test;

class RegistrationPropertiesTest {

    @Test
    void onlyAnEnforcedConfigurationBlocksUnverifiedAccounts() {
        assertThat(new RegistrationProperties(true, 30, false).blocksUnverified()).isTrue();
        assertThat(new RegistrationProperties(true, 30, true).blocksUnverified()).isFalse();
        assertThat(new RegistrationProperties(false, 30, false).blocksUnverified()).isFalse();
    }
}
