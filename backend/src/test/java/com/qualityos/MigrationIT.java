package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Runs the migrations on an empty database of their own, so that each test sees them apply from the start. */
class MigrationIT extends AbstractIntegrationTest {

    private record Database(String url, Flyway flyway, JdbcTemplate owner) {
    }

    private Database emptyDatabase(String target) {
        String name = "migration_" + UUID.randomUUID().toString().replace("-", "");
        asDatabaseSuperuser().execute("CREATE DATABASE " + name + " OWNER qualityos_migrator");
        String url = databaseUrl().replaceFirst("/qualityos\\b", "/" + name);
        var configuration = Flyway.configure()
                .dataSource(url, "qualityos_migrator", "migrator")
                .locations("classpath:db/migration")
                .placeholders(java.util.Map.of("approle", "qualityos_app"));
        Flyway flyway = (target == null ? configuration : configuration.target(MigrationVersion.fromVersion(target))).load();
        return new Database(url, flyway, new JdbcTemplate(new DriverManagerDataSource(url, superuser(), superuserPassword())));
    }

    @Test
    void everyMigrationAppliesToAnEmptyDatabaseAndValidates() {
        Database database = emptyDatabase(null);
        assertThat(database.flyway().migrate().success).isTrue();
        database.flyway().validate();
        assertThat(database.flyway().info().pending()).isEmpty();
    }

    @Test
    void obsoleteAndDuplicateTablesWereNeverCreated() {
        Database database = emptyDatabase(null);
        database.flyway().migrate();
        assertThat(database.owner().queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('email_verification_tokens', 'email_preferences', 'email_templates')
                """, String.class)).isEmpty();
        assertThat(database.owner().queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN ('account_tokens', 'notification_preferences')
                """, String.class)).hasSize(2);
    }

    @Test
    void olderDispatchesCountAsShippedUnlessTheyWereCancelled() {
        Database database = emptyDatabase("12");
        database.flyway().migrate();
        JdbcTemplate sql = database.owner();
        UUID tenant = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        UUID live = UUID.randomUUID();
        UUID cancelled = UUID.randomUUID();
        sql.update("INSERT INTO tenants (id, name) VALUES (?, 'Acme')", tenant);
        sql.update("INSERT INTO customers (id, tenant_id, code, name) VALUES (?, ?, 'C', 'Tata')", customer, tenant);
        for (UUID dispatch : List.of(live, cancelled)) {
            sql.update("""
                    INSERT INTO dispatch_records (id, tenant_id, dispatch_number, customer_id, dispatched_on)
                    VALUES (?, ?, ?, ?, DATE '2026-01-15')
                    """, dispatch, tenant, dispatch.toString(), customer);
        }
        sql.update("INSERT INTO trace_corrections (id, tenant_id, dispatch_id, reason) VALUES (?, ?, ?, 'x')",
                UUID.randomUUID(), tenant, cancelled);

        Flyway latest = Flyway.configure().configuration(database.flyway().getConfiguration())
                .target(MigrationVersion.LATEST).load();
        assertThat(latest.migrate().success).isTrue();

        assertThat(sql.queryForList("SELECT dispatch_id FROM dispatch_shipments", UUID.class)).containsExactly(live);
        assertThat(sql.queryForObject("SELECT shipped_on::text FROM dispatch_shipments", String.class)).isEqualTo("2026-01-15");
        assertThat(sql.queryForObject(
                "SELECT count(*) FROM pg_class WHERE relname IN ('dispatch_records', 'trace_corrections', 'dispatch_shipments') "
                        + "AND relforcerowsecurity", Integer.class)).isEqualTo(3);
    }
}
