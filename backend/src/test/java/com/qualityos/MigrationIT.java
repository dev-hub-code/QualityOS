package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Runs the migrations on an empty database of their own, so that each test sees them apply from the start. */
class MigrationIT extends AbstractIntegrationTest {

    private record Database(String url, Flyway flyway, JdbcTemplate owner) {
    }

    private Database emptyDatabase() {
        String name = "migration_" + UUID.randomUUID().toString().replace("-", "");
        asDatabaseSuperuser().execute("CREATE DATABASE " + name + " OWNER qualityos_migrator");
        String url = databaseUrl().replaceFirst("/qualityos\\b", "/" + name);
        Flyway flyway = Flyway.configure()
                .dataSource(url, "qualityos_migrator", "migrator")
                .locations("classpath:db/migration")
                .placeholders(java.util.Map.of("approle", "qualityos_app"))
                .load();
        return new Database(url, flyway, new JdbcTemplate(new DriverManagerDataSource(url, superuser(), superuserPassword())));
    }

    @Test
    void everyMigrationAppliesToAnEmptyDatabaseAndValidates() {
        Database database = emptyDatabase();
        assertThat(database.flyway().migrate().success).isTrue();
        database.flyway().validate();
        assertThat(database.flyway().info().pending()).isEmpty();
    }

    @Test
    void obsoleteAndDuplicateTablesWereNeverCreated() {
        Database database = emptyDatabase();
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
}
