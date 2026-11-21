package com.qualityos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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

    /**
     * A foreign key the application looks rows up by needs an index leading with it (after tenant_id, which every query
     * has under RLS). These are only checked when a parent row is deleted, which reference rows never are, so they are
     * left unindexed on purpose. A new key either gets an index or joins this list after review.
     */
    private static final java.util.Set<String> UNINDEXED_BY_DESIGN = java.util.Set.of(
            "audit_findings.audit_item_id",
            "audits.next_audit_id",
            "batch_relationships.entered_uom_id",
            "bom_lines.uom_id",
            "capa_actions.owner_id",
            "corrective_actions.closed_by",
            "corrective_actions.owner_id",
            "inspection_parameters.uom_id",
            "inspections.template_version_id",
            "material_correction_approvals.approver_id",
            "material_corrections.replacement_relationship_id",
            "material_receipt_items.purchase_order_item_id",
            "material_receipt_items.uom_id",
            "material_reservations.uom_id",
            "material_reservations.work_order_material_id",
            "material_specifications.uom_id",
            "materials.category_id",
            "materials.uom_id",
            "non_conformances.assigned_to",
            "non_conformances.closed_by",
            "non_conformances.defect_category_id",
            "non_conformances.rejected_uom_id",
            "non_conformances.verification_requested_by",
            "platform_admins.added_by",
            "product_materials.uom_id",
            "product_specifications.uom_id",
            "products.category_id",
            "products.uom_id",
            "purchase_order_items.uom_id",
            "rework_orders.reinspection_id",
            "rework_orders.source_inspection_id",
            "rework_orders.uom_id",
            "subscription_invoices.subscription_id",
            "suppliers.category_id",
            "support_access_events.grant_id",
            "support_notes.author_id",
            "support_tickets.opened_by",
            "work_centers.production_line_id",
            "work_order_material_movements.lot_id",
            "work_order_material_movements.reservation_id",
            "work_order_material_movements.source_batch_id",
            "work_order_material_movements.uom_id",
            "work_order_material_movements.work_order_material_id",
            "work_order_materials.uom_id",
            "work_order_quality_gates.checkpoint_id",
            "work_orders.production_line_id",
            "work_orders.uom_id");

    /** Shared rows of these have no tenant, so a link to them cannot include one. */
    private static final java.util.Set<String> SHARED_ROWS = java.util.Set.of("units_of_measure", "roles");

    @Test
    void linksBetweenOrganisationsRecordsIncludeTheTenant() {
        List<String> unscoped = asDatabaseSuperuser().queryForList("""
                SELECT c.conrelid::regclass || '.' || c.conname || ' -> ' || c.confrelid::regclass
                FROM pg_constraint c
                WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace
                  AND c.confrelid::regclass::text <> ALL (?)
                  AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.conrelid
                      AND a.attname = 'tenant_id' AND NOT a.attisdropped)
                  AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.confrelid
                      AND a.attname = 'tenant_id' AND NOT a.attisdropped)
                  AND NOT EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.conrelid
                      AND a.attname = 'tenant_id' AND a.attnum = ANY (c.conkey))
                ORDER BY 1
                """, String.class, (Object) SHARED_ROWS.toArray(String[]::new));
        assertThat(unscoped).as("foreign keys between tenant tables that leave out tenant_id").isEmpty();
    }

    @Test
    void everyForeignKeyIsIndexedOrReviewed() {
        List<String> unindexed = asDatabaseSuperuser().queryForList("""
                WITH fk AS (
                    SELECT c.conrelid::regclass::text AS tbl, a.attname AS col
                    FROM pg_constraint c
                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[array_length(c.conkey, 1)]
                    WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace
                ), idx AS (
                    SELECT i.indrelid::regclass::text AS tbl, array_agg(a.attname ORDER BY k.n) AS cols
                    FROM pg_index i CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY k(attnum, n)
                    JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                    WHERE k.n <= 2 GROUP BY i.indexrelid, i.indrelid
                )
                SELECT DISTINCT fk.tbl || '.' || fk.col FROM fk
                WHERE fk.col <> 'tenant_id' AND NOT EXISTS (SELECT 1 FROM idx WHERE idx.tbl = fk.tbl
                    AND (idx.cols[1] = fk.col OR (idx.cols[1] = 'tenant_id' AND idx.cols[2] = fk.col)))
                """, String.class);
        assertThat(unindexed).allMatch(UNINDEXED_BY_DESIGN::contains,
                "indexed, or listed in UNINDEXED_BY_DESIGN after review");
    }
}
