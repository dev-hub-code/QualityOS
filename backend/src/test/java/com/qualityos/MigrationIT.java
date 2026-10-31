package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void qualityRecordsTakeTheirFacilityFromWhatRaisedThem() {
        Database database = emptyDatabase("19");
        database.flyway().migrate();
        JdbcTemplate sql = database.owner();
        UUID tenant = UUID.randomUUID();
        UUID facility = UUID.randomUUID();
        UUID audit = UUID.randomUUID();
        UUID lead = UUID.randomUUID();
        UUID checklist = UUID.randomUUID();
        UUID finding = UUID.randomUUID();
        UUID placed = UUID.randomUUID();
        UUID manual = UUID.randomUUID();
        UUID capa = UUID.randomUUID();
        sql.execute("ALTER TABLE tenants NO FORCE ROW LEVEL SECURITY");
        List<String> tables = List.of(
                "facilities", "audits", "audit_checklists", "audit_findings", "non_conformances", "corrective_actions");
        tables.forEach(table -> sql.execute("ALTER TABLE " + table + " DISABLE ROW LEVEL SECURITY"));
        sql.update("INSERT INTO tenants (id, name) VALUES (?, 'Acme')", tenant);
        sql.update("INSERT INTO facilities (id, tenant_id, code, name) VALUES (?, ?, 'P', 'Pune')", facility, tenant);
        sql.update("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, 'a@example.com', 'x', 'A')", lead);
        sql.update("INSERT INTO audit_checklists (id, tenant_id, code, name) VALUES (?, ?, 'C', 'C')", checklist, tenant);
        sql.update("""
                INSERT INTO audits (id, tenant_id, audit_number, audit_type, title, scope, facility_id, planned_on,
                    lead_auditor_id, checklist_id)
                VALUES (?, ?, 'AUD-1', 'INTERNAL', 'T', 'S', ?, DATE '2026-01-15', ?, ?)
                """, audit, tenant, facility, lead, checklist);
        sql.update("""
                INSERT INTO audit_findings (id, tenant_id, audit_id, grade, statement, evidence)
                VALUES (?, ?, ?, 'MINOR_NONCONFORMITY', 'S', 'E')
                """, finding, tenant, audit);
        for (UUID ncr : List.of(placed, manual)) {
            sql.update("""
                    INSERT INTO non_conformances (id, tenant_id, ncr_number, source, audit_id, audit_finding_id, severity,
                        title, description)
                    VALUES (?, ?, ?, ?, ?, ?, 'MINOR', 'T', 'D')
                    """, ncr, tenant, ncr.toString(), ncr.equals(placed) ? "AUDIT" : "MANUAL",
                    ncr.equals(placed) ? audit : null, ncr.equals(placed) ? finding : null);
        }
        sql.update("""
                UPDATE non_conformances SET status = 'CLOSED', closed_at = now(), closed_by = ?, outcome_note = 'N',
                    root_cause = 'R', containment_action = 'C'
                WHERE id = ?
                """, lead, placed);
        sql.update("""
                INSERT INTO corrective_actions (id, tenant_id, capa_number, ncr_id, title, problem_statement, owner_id,
                    target_date)
                VALUES (?, ?, 'CAPA-1', ?, 'T', 'P', ?, DATE '2026-02-15')
                """, capa, tenant, placed, lead);
        sql.update("""
                UPDATE corrective_actions SET status = 'CLOSED', closed_at = now(), closed_by = ?, outcome_note = 'N',
                    root_cause = 'R'
                WHERE id = ?
                """, lead, capa);
        String touched = "SELECT updated_at::text FROM non_conformances WHERE id = '" + placed + "'";
        String updatedAt = sql.queryForObject(touched, String.class);
        tables.forEach(table -> sql.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY"));
        sql.execute("ALTER TABLE tenants FORCE ROW LEVEL SECURITY");

        Flyway latest = Flyway.configure().configuration(database.flyway().getConfiguration())
                .target(MigrationVersion.LATEST).load();
        assertThat(latest.migrate().success).isTrue();

        tables.forEach(table -> sql.execute("ALTER TABLE " + table + " DISABLE ROW LEVEL SECURITY"));
        assertThat(sql.queryForObject("SELECT facility_id FROM non_conformances WHERE id = ?", UUID.class, placed))
                .isEqualTo(facility);
        assertThat(sql.queryForObject(touched, String.class)).isEqualTo(updatedAt);
        assertThat(sql.queryForObject("SELECT facility_id FROM corrective_actions WHERE id = ?", UUID.class, capa))
                .isEqualTo(facility);
        assertThat(sql.queryForObject("SELECT facility_id FROM non_conformances WHERE id = ?", UUID.class, manual))
                .isNull();
        assertThatThrownBy(() -> sql.update("UPDATE non_conformances SET title = 'Changed' WHERE id = ?", placed))
                .hasMessageContaining("cannot be changed");
        assertThatThrownBy(() -> sql.update("UPDATE corrective_actions SET title = 'Changed' WHERE id = ?", capa))
                .hasMessageContaining("cannot be changed");
    }
}
