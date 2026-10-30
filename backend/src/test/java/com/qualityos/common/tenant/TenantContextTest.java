package com.qualityos.common.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qualityos.common.exception.QualityOsException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TenantContextTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @Test
    void missingTenantIsAnErrorNotNull() {
        assertThatThrownBy(TenantContext::getCurrentTenantId).isInstanceOf(QualityOsException.class);
        assertThat(TenantContext.findCurrentTenantId()).isEmpty();
    }

    @Test
    void scopeIsClearedAfterwardsEvenOnFailure() {
        assertThatThrownBy(() -> TenantContext.runAs(tenant, user, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(TenantContext.findCurrentTenantId()).isEmpty();
    }

    @Test
    void nestedScopeRestoresTheOuterOne() {
        UUID other = UUID.randomUUID();

        TenantContext.runAs(tenant, user, () -> {
            TenantContext.runAs(other, user, () ->
                    assertThat(TenantContext.getCurrentTenantId()).isEqualTo(other));
            assertThat(TenantContext.getCurrentTenantId()).isEqualTo(tenant);
        });
    }

    @Test
    void cannotChangeInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> TenantContext.open(tenant, user)).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
