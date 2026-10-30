package com.qualityos.common.config;

import com.qualityos.common.tenant.TenantAwareTransactionManager;
import com.qualityos.common.tenant.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.orm.jpa.JpaTransactionManager;

@Configuration
@EnableJpaAuditing
public class PersistenceConfig {

    /** Matches no tenant's rows, so that Hibernate's filter fails closed without a tenant context. */
    private static final UUID NO_TENANT = new UUID(0, 0);

    @Bean
    JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new TenantAwareTransactionManager(entityManagerFactory);
    }

    @Bean
    AuditorAware<UUID> auditorAware() {
        return TenantContext::findCurrentUserId;
    }

    @Bean
    HibernatePropertiesCustomizer tenantResolverCustomizer() {
        CurrentTenantIdentifierResolver<UUID> resolver = new CurrentTenantIdentifierResolver<>() {
            @Override
            public UUID resolveCurrentTenantIdentifier() {
                Optional<UUID> tenantId = TenantContext.findCurrentTenantId();
                return tenantId.orElse(NO_TENANT);
            }

            @Override
            public boolean validateExistingCurrentSessions() {
                return false;
            }
        };
        return properties -> properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }
}
