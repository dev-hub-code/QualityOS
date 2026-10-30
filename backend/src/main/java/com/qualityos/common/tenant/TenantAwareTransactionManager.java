package com.qualityos.common.tenant;

import jakarta.persistence.EntityManagerFactory;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Binds {@link TenantContext} to the database connection as each transaction begins, for row-level security.
 * The settings are transaction-local, so they cannot leak to the next user of a pooled connection.
 */
public class TenantAwareTransactionManager extends JpaTransactionManager {

    private static final String BIND_SQL =
            "SELECT set_config('app.tenant_id', ?, true), set_config('app.user_id', ?, true)";

    public TenantAwareTransactionManager(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        String tenantId = text(TenantContext.findCurrentTenantId().orElse(null));
        String userId = text(TenantContext.findCurrentUserId().orElse(null));
        try {
            holder.getEntityManager().unwrap(Session.class).doWork(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(BIND_SQL)) {
                    statement.setString(1, tenantId);
                    statement.setString(2, userId);
                    statement.execute();
                }
            });
        } catch (RuntimeException ex) {
            holder.getEntityManager().getTransaction().rollback();
            doCleanupAfterCompletion(transaction);
            throw ex;
        }
    }

    private static String text(UUID id) {
        return id == null ? "" : id.toString();
    }
}
